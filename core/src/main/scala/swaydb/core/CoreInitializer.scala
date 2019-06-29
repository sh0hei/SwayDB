/*
 * Copyright (c) 2019 Simer Plaha (@simerplaha)
 *
 * This file is a part of SwayDB.
 *
 * SwayDB is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * SwayDB is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with SwayDB. If not, see <https://www.gnu.org/licenses/>.
 */

package swaydb.core

import java.nio.file.Paths

import com.typesafe.scalalogging.LazyLogging
import swaydb.core.actor.WiredActor
import swaydb.core.function.FunctionStore
import swaydb.core.group.compression.data.KeyValueGroupingStrategyInternal
import swaydb.core.io.file.BufferCleaner
import swaydb.core.io.file.IOEffect._
import swaydb.core.level.compaction._
import swaydb.core.level.zero.LevelZero
import swaydb.core.level.{Level, NextLevel, TrashLevel}
import swaydb.core.queue.{FileLimiter, KeyValueLimiter}
import swaydb.data.IO
import swaydb.data.compaction.CompactionExecutionContext
import swaydb.data.config._
import swaydb.data.order.{KeyOrder, TimeOrder}
import swaydb.data.slice.Slice
import swaydb.data.storage.{AppendixStorage, LevelStorage}
import swaydb.core.segment.format.a.{SegmentCompression, block}

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.FiniteDuration

private[core] object CoreInitializer extends LazyLogging {

  /**
    * Closes all the open files and releases the locks on database folders.
    */
  private def addShutdownHook(zero: LevelZero,
                              compactor: Option[WiredActor[CompactionStrategy[CompactorState], CompactorState]])(implicit compactionStrategy: CompactionStrategy[CompactorState]): Unit =
    sys.addShutdownHook {
      logger.info("Shutting down compaction.")
      IO(compactor foreach compactionStrategy.terminate) onFailureSideEffect {
        error =>
          logger.error("Failed compaction shutdown.", error.exception)
      }

      logger.info("Closing files.")
      zero.close onFailureSideEffect {
        error =>
          logger.error("Failed to close Levels.", error.exception)
      }

      logger.info("Releasing database locks.")
      zero.releaseLocks onFailureSideEffect {
        error =>
          logger.error("Failed to release locks.", error.exception)
      }
    }

  def apply(config: LevelZeroConfig,
            bufferCleanerEC: ExecutionContext)(implicit keyOrder: KeyOrder[Slice[Byte]],
                                               timeOrder: TimeOrder[Slice[Byte]],
                                               functionStore: FunctionStore): IO[BlockingCore[IO]] = {
    implicit val fileLimiter = FileLimiter.empty
    implicit val compactionStrategy: CompactionStrategy[CompactorState] = Compactor
    if (config.storage.isMMAP) BufferCleaner.initialiseCleaner(bufferCleanerEC)

    LevelZero(
      mapSize = config.mapSize,
      storage = config.storage,
      nextLevel = None,
      throttle = config.throttle,
      acceleration = config.acceleration
    ) map {
      zero =>
        addShutdownHook(zero, None)
        BlockingCore[IO](zero, () => IO.unit)
    }
  }

  def executionContext(levelConfig: LevelConfig): Option[CompactionExecutionContext] =
    levelConfig match {
      case TrashLevelConfig =>
        None

      case config: MemoryLevelConfig =>
        Some(config.compactionExecutionContext)

      case config: PersistentLevelConfig =>
        Some(config.compactionExecutionContext)
    }

  def executionContexts(config: SwayDBConfig): List[CompactionExecutionContext] =
    List(config.level0.compactionExecutionContext) ++
      executionContext(config.level1).toList ++
      config.otherLevels.flatMap(executionContext)

  def startCompaction(zero: LevelZero,
                      executionContexts: List[CompactionExecutionContext],
                      copyForwardAllOnStart: Boolean)(implicit compactionStrategy: CompactionStrategy[CompactorState],
                                                      compactionOrdering: CompactionOrdering): IO[Option[WiredActor[CompactionStrategy[CompactorState], CompactorState]]] =
    compactionStrategy.createAndListen(
      zero = zero,
      executionContexts = executionContexts,
      copyForwardAllOnStart = copyForwardAllOnStart
    ) map (Some(_))

  def sendInitialWakeUp(compactor: WiredActor[CompactionStrategy[CompactorState], CompactorState]): Unit =
    compactor send {
      (impl, state, self) =>
        impl.wakeUp(
          state = state,
          forwardCopyOnAllLevels = true,
          self = self
        )
    }

  def apply(config: SwayDBConfig,
            maxSegmentsOpen: Int,
            cacheSize: Long,
            keyValueQueueDelay: FiniteDuration,
            segmentCloserDelay: FiniteDuration,
            fileOpenLimiterEC: ExecutionContext,
            cacheLimiterEC: ExecutionContext)(implicit keyOrder: KeyOrder[Slice[Byte]],
                                              timeOrder: TimeOrder[Slice[Byte]],
                                              functionStore: FunctionStore): IO[BlockingCore[IO]] = {
    implicit val fileOpenLimiter: FileLimiter =
      FileLimiter(maxSegmentsOpen, segmentCloserDelay)(fileOpenLimiterEC)

    implicit val keyValueLimiter: KeyValueLimiter =
      KeyValueLimiter(cacheSize, keyValueQueueDelay)(cacheLimiterEC)

    implicit val compactionStrategy: CompactionStrategy[CompactorState] =
      Compactor

    implicit val compactionOrdering: CompactionOrdering =
      DefaultCompactionOrdering

    BufferCleaner.initialiseCleaner(fileOpenLimiterEC)

    def createLevel(id: Long,
                    nextLevel: Option[NextLevel],
                    config: LevelConfig): IO[NextLevel] =
      config match {
        case config: MemoryLevelConfig =>
          implicit val compression: Option[KeyValueGroupingStrategyInternal] = config.groupingStrategy map KeyValueGroupingStrategyInternal.apply
          Level(
            segmentSize = config.segmentSize,
            bloomFilterConfig = block.BloomFilter.Config(config = config.bloomFilter),
            hashIndexConfig = block.HashIndex.Config(config = HashIndex.Disable),
            binarySearchIndexConfig = block.BinarySearchIndex.Config(config = BinarySearchIndex.Disable),
            sortedIndexConfig = block.SortedIndex.Config(cacheOnRead = false, prefixCompressionResetCount = 0),
            valuesConfig =
              block.Values.Config(
                compressDuplicateValues = false,
                compressDuplicateRangeValues = false,
                duplicateValueSearchCount = 0,
                cacheOnRead = false
              ),
            segmentCompression = SegmentCompression.empty,
            levelStorage = LevelStorage.Memory(dir = Paths.get("MEMORY_LEVEL").resolve(id.toString)),
            appendixStorage = AppendixStorage.Memory,
            nextLevel = nextLevel,
            pushForward = config.copyForward,
            throttle = config.throttle,
            deleteSegmentsEventually = config.deleteSegmentsEventually
          )

        case config: PersistentLevelConfig =>
          implicit val compression: Option[KeyValueGroupingStrategyInternal] = config.groupingStrategy map KeyValueGroupingStrategyInternal.apply
          Level(
            segmentSize = config.segmentSize,
            bloomFilterConfig = block.BloomFilter.Config(config = config.bloomFilter),
            hashIndexConfig = block.HashIndex.Config(config = config.hashIndex),
            binarySearchIndexConfig = block.BinarySearchIndex.Config(config = config.binarySearchIndex),
            sortedIndexConfig = block.SortedIndex.Config(config.sortedIndex.toOption.get),
            valuesConfig = block.Values.Config(config.values),
            segmentCompression =
              SegmentCompression(
                bloomFilter = config.bloomFilter,
                hashIndex = config.hashIndex,
                binarySearchIndex = config.binarySearchIndex,
                sortedIndex = config.sortedIndex,
                values = config.values
              ),
            levelStorage =
              LevelStorage.Persistent(
                mmapSegmentsOnWrite = config.mmapSegment.mmapWrite,
                mmapSegmentsOnRead = config.mmapSegment.mmapRead,
                dir = config.dir.resolve(id.toString),
                otherDirs = config.otherDirs.map(dir => dir.copy(path = dir.path.resolve(id.toString)))
              ),
            nextLevel = nextLevel,
            pushForward = config.copyForward,
            appendixStorage = AppendixStorage.Persistent(config.mmapAppendix, config.appendixFlushCheckpointSize),
            throttle = config.throttle,
            deleteSegmentsEventually = config.deleteSegmentsEventually
          )

        case TrashLevelConfig =>
          IO.Success(TrashLevel)
      }

    def createLevels(levelConfigs: List[LevelConfig],
                     previousLowerLevel: Option[NextLevel]): IO[BlockingCore[IO]] =
      levelConfigs match {
        case Nil =>
          createLevel(1, previousLowerLevel, config.level1) flatMap {
            level1 =>
              LevelZero(
                mapSize = config.level0.mapSize,
                storage = config.level0.storage,
                nextLevel = Some(level1),
                throttle = config.level0.throttle,
                acceleration = config.level0.acceleration
              ) flatMap {
                zero =>
                  startCompaction(
                    zero = zero,
                    executionContexts = executionContexts(config),
                    copyForwardAllOnStart = true
                  ) map {
                    compactor =>
                      addShutdownHook(
                        zero = zero,
                        compactor = compactor
                      )

                      //trigger initial wakeUp.
                      compactor foreach sendInitialWakeUp

                      BlockingCore(
                        zero = zero,
                        onClose = () => IO(compactor foreach compactionStrategy.terminate)
                      )
                  }
              }
          }

        case lowestLevelConfig :: upperLevelConfigs =>

          val levelNumber: Long =
            previousLowerLevel
              .flatMap(_.paths.headOption.map(_.path.folderId - 1))
              .getOrElse(levelConfigs.size + 1)

          createLevel(levelNumber, previousLowerLevel, lowestLevelConfig) flatMap {
            newLowerLevel =>
              createLevels(upperLevelConfigs, Some(newLowerLevel))
          }
      }

    logger.info(s"Starting ${config.otherLevels.size} configured Levels.")
    createLevels(config.otherLevels.reverse, None)
  }
}
