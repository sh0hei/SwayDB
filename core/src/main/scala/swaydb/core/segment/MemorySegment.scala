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

package swaydb.core.segment

import java.nio.file.Path
import java.util.function.Consumer

import com.typesafe.scalalogging.LazyLogging
import swaydb.Aggregator
import swaydb.core.actor.FileSweeper
import swaydb.core.data.{Memory, _}
import swaydb.core.function.FunctionStore
import swaydb.core.level.PathsDistributor
import swaydb.core.segment.format.a.block._
import swaydb.core.segment.format.a.block.binarysearch.BinarySearchIndexBlock
import swaydb.core.segment.format.a.block.hashindex.HashIndexBlock
import swaydb.core.segment.merge.{MergeStats, SegmentMerger}
import swaydb.core.util._
import swaydb.data.MaxKey
import swaydb.data.order.{KeyOrder, TimeOrder}
import swaydb.data.slice.{Slice, SliceOption}

import scala.collection.mutable.ListBuffer
import scala.concurrent.duration.Deadline
import scala.jdk.CollectionConverters._

private[segment] case class MemorySegment(path: Path,
                                          segmentId: Long,
                                          minKey: Slice[Byte],
                                          maxKey: MaxKey[Slice[Byte]],
                                          minMaxFunctionId: Option[MinMax[Slice[Byte]]],
                                          segmentSize: Int,
                                          hasRange: Boolean,
                                          hasPut: Boolean,
                                          createdInLevel: Int,
                                          private[segment] val skipList: SkipList.Immutable[SliceOption[Byte], MemoryOptional, Slice[Byte], Memory],
                                          nearestExpiryDeadline: Option[Deadline])(implicit keyOrder: KeyOrder[Slice[Byte]],
                                                                                   timeOrder: TimeOrder[Slice[Byte]],
                                                                                   functionStore: FunctionStore,
                                                                                   fileSweeper: FileSweeper.Enabled) extends Segment with LazyLogging {

  @volatile private var deleted = false

  import keyOrder._

  override def put(newKeyValues: Slice[KeyValue],
                   minSegmentSize: Int,
                   removeDeletes: Boolean,
                   createdInLevel: Int,
                   valuesConfig: ValuesBlock.Config,
                   sortedIndexConfig: SortedIndexBlock.Config,
                   binarySearchIndexConfig: BinarySearchIndexBlock.Config,
                   hashIndexConfig: HashIndexBlock.Config,
                   bloomFilterConfig: BloomFilterBlock.Config,
                   segmentConfig: SegmentBlock.Config,
                   pathsDistributor: PathsDistributor)(implicit idGenerator: IDGenerator): Slice[Segment] =
    if (deleted) {
      throw swaydb.Exception.NoSuchFile(path)
    } else {
      val stats = MergeStats.memory[Memory, ListBuffer](ListBuffer.newBuilder)

      SegmentMerger.merge(
        newKeyValues = newKeyValues,
        oldKeyValuesCount = getKeyValueCount(),
        oldKeyValues = iterator(),
        stats = stats,
        isLastLevel = removeDeletes
      )

      Segment.memory(
        createdInLevel = createdInLevel,
        minSegmentSize = minSegmentSize,
        keyValues = stats.close,
        pathsDistributor = pathsDistributor
      )
    }

  override def refresh(minSegmentSize: Int,
                       removeDeletes: Boolean,
                       createdInLevel: Int,
                       valuesConfig: ValuesBlock.Config,
                       sortedIndexConfig: SortedIndexBlock.Config,
                       binarySearchIndexConfig: BinarySearchIndexBlock.Config,
                       hashIndexConfig: HashIndexBlock.Config,
                       bloomFilterConfig: BloomFilterBlock.Config,
                       segmentConfig: SegmentBlock.Config,
                       pathsDistributor: PathsDistributor)(implicit idGenerator: IDGenerator): Slice[Segment] =
    if (deleted) {
      throw swaydb.Exception.NoSuchFile(path)
    } else {
      val keyValues =
        Segment
          .toMemoryIterator(iterator(), removeDeletes)
          .to(Iterable)

      val mergeStats =
        new MergeStats.Memory.Closed[Iterable](
          isEmpty = false,
          keyValues = keyValues
        )

      Segment.memory(
        minSegmentSize = minSegmentSize,
        pathsDistributor = pathsDistributor,
        createdInLevel = createdInLevel,
        keyValues = mergeStats
      )
    }

  override def getFromCache(key: Slice[Byte]): KeyValueOptional =
    skipList.get(key)

  override def get(key: Slice[Byte], readState: ReadState): MemoryOptional =
    if (deleted)
      throw swaydb.Exception.NoSuchFile(path)
    else
      maxKey match {
        case MaxKey.Fixed(maxKey) if key > maxKey =>
          Memory.Null

        case range: MaxKey.Range[Slice[Byte]] if key >= range.maxKey =>
          Memory.Null

        case _ =>
          if (hasRange)
            skipList.floor(key) match {
              case range: Memory.Range if range contains key =>
                range

              case _ =>
                skipList.get(key)
            }
          else
            skipList.get(key)
      }

  def mightContainKey(key: Slice[Byte]): Boolean =
    true

  override def mightContainFunction(key: Slice[Byte]): Boolean =
    minMaxFunctionId.exists {
      minMaxFunctionId =>
        MinMax.contains(
          key = key,
          minMax = minMaxFunctionId
        )(FunctionStore.order)
    }

  override def lower(key: Slice[Byte],
                     readState: ReadState): MemoryOptional =
    if (deleted)
      throw swaydb.Exception.NoSuchFile(path)
    else
      skipList.lower(key)

  def floorHigherHint(key: Slice[Byte]): Option[Slice[Byte]] =
    if (deleted)
      throw swaydb.Exception.NoSuchFile(path)
    else if (hasPut)
      if (key < minKey)
        Some(minKey)
      else if (key < maxKey.maxKey)
        Some(key)
      else
        None
    else
      None

  override def higher(key: Slice[Byte],
                      readState: ReadState): MemoryOptional =
    if (deleted)
      throw swaydb.Exception.NoSuchFile(path)
    else if (hasRange)
      skipList.floor(key) match {
        case floorRange: Memory.Range if floorRange contains key =>
          floorRange

        case _ =>
          skipList.higher(key)
      }
    else
      skipList.higher(key)

  override def getAll(): Slice[KeyValue] = {
    val slice = Slice.newAggregator[KeyValue](skipList.size)
    getAll(slice)
    slice.result
  }

  override def getAll[T](aggregator: Aggregator[KeyValue, T]): Unit =
    if (deleted)
      throw swaydb.Exception.NoSuchFile(path)
    else
      skipList.values() forEach {
        new Consumer[Memory] {
          override def accept(value: Memory): Unit =
            aggregator add value
        }
      }

  override def iterator(): Iterator[KeyValue] =
    skipList.values().iterator().asScala

  override def delete: Unit = {
    //cache should not be cleared.
    logger.trace(s"{}: DELETING FILE", path)
    if (deleted)
      throw swaydb.Exception.NoSuchFile(path)
    else
      deleted = true
  }

  override val close: Unit =
    ()

  override def getKeyValueCount(): Int =
    if (deleted)
      throw swaydb.Exception.NoSuchFile(path)
    else
      skipList.size

  override def isOpen: Boolean =
    !deleted

  override def isFileDefined: Boolean =
    !deleted

  override def memory: Boolean =
    true

  override def persistent: Boolean =
    false

  override def existsOnDisk: Boolean =
    false

  override def isFooterDefined: Boolean =
    !deleted

  override def deleteSegmentsEventually: Unit =
    fileSweeper.delete(this)

  override def clearCachedKeyValues(): Unit =
    ()

  override def clearAllCaches(): Unit =
    ()

  override def isInKeyValueCache(key: Slice[Byte]): Boolean =
    skipList contains key

  override def isKeyValueCacheEmpty: Boolean =
    skipList.isEmpty

  def areAllCachesEmpty: Boolean =
    isKeyValueCacheEmpty

  override def cachedKeyValueSize: Int =
    skipList.size
}
