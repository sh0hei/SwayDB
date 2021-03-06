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

package swaydb.core.segment.format.a.block.hashindex

import org.scalatest.OptionValues._
import swaydb.core.CommonAssertions._
import swaydb.core.RunThis._
import swaydb.core.TestData._
import swaydb.core.data.Transient
import swaydb.core.segment.format.a.block.hashindex.HashIndexBlock.HashIndexBlockOps
import swaydb.core.segment.format.a.block.reader.BlockRefReader
import swaydb.core.segment.format.a.block.{Block, SortedIndexBlock}
import swaydb.core.{TestBase, TestSweeper}
import swaydb.data.config.RandomKeyIndex.RequiredSpace
import swaydb.data.order.KeyOrder
import swaydb.data.slice.Slice

import scala.collection.mutable
import scala.collection.mutable.ListBuffer

class HashIndexBlockSpec extends TestBase {

  implicit val keyOrder = KeyOrder.default

  val keyValueCount = 10000

  implicit val blockCacheMemorySweeper = TestSweeper.memorySweeperBlock

  import keyOrder._

  "optimalBytesRequired" should {
    "allocate optimal byte" in {
      HashIndexBlock.optimalBytesRequired(
        keyCounts = 1,
        writeAbleLargestValueSize = 1,
        allocateSpace = _.requiredSpace,
        hasCompression = false,
        minimumNumberOfKeys = 0,
        copyIndex = randomBoolean()
      ) shouldBe
        HashIndexBlock.headerSize(
          keyCounts = 1,
          hasCompression = false,
          writeAbleLargestValueSize = 1
        ) + 1 + 1
    }
  }

  "it" should {
    "write compressed HashIndex and result in the same as uncompressed HashIndex" in {
      runThis(100.times, log = true) {
        val maxProbe = 10

        def allocateMoreSpace(requiredSpace: RequiredSpace) = requiredSpace.requiredSpace * 10

        val copyIndex = randomBoolean()

        val uncompressedKeyValues =
          randomKeyValues(
            count = 1000,
            startId = Some(1),
//            addRemoves = true,
//            addFunctions = true,
//            addRemoveDeadlines = true,
//            addUpdates = true,
//            addPendingApply = true,
            hashIndexConfig =
              HashIndexBlock.Config(
                allocateSpace = allocateMoreSpace,
                compressions = _ => Seq.empty,
                copyIndex = copyIndex,
                maxProbe = maxProbe,
                minimumNumberOfKeys = 0,
                minimumNumberOfHits = 0,
                ioStrategy = _ => randomIOAccess()
              ),
            sortedIndexConfig =
              SortedIndexBlock.Config.random.copy(prefixCompressionResetCount = 0)
          )

        uncompressedKeyValues should not be empty

        val uncompressedState =
          HashIndexBlock.init(keyValues = uncompressedKeyValues).value

        val compressedState =
          HashIndexBlock.init(
            keyValues =
              uncompressedKeyValues
                .updateStats(
                  hashIndexConfig =
                    HashIndexBlock.Config(
                      allocateSpace = allocateMoreSpace,
                      compressions = _ => randomCompressionsLZ4OrSnappy(),
                      copyIndex = copyIndex,
                      maxProbe = maxProbe,
                      minimumNumberOfKeys = 0,
                      minimumNumberOfHits = 0,
                      ioStrategy = _ => randomIOAccess()
                    )
                )
          ).get

        uncompressedKeyValues foreach {
          keyValue =>
            val uncompressedWriteResult =
              HashIndexBlock.write(
                key = keyValue.key,
                value = keyValue.stats.thisKeyValuesAccessIndexOffset,
                state = uncompressedState
              )

            val compressedWriteResult =
              HashIndexBlock.write(
                key = keyValue.key,
                value = keyValue.stats.thisKeyValuesAccessIndexOffset,
                state = compressedState
              )

            uncompressedWriteResult shouldBe compressedWriteResult
        }

        HashIndexBlock.close(uncompressedState)
        HashIndexBlock.close(compressedState)

        //compressed bytes should be smaller
        compressedState.bytes.size should be <= uncompressedState.bytes.size

        val uncompressedHashIndex = Block.unblock[HashIndexBlock.Offset, HashIndexBlock](uncompressedState.bytes)

        val compressedHashIndex = Block.unblock[HashIndexBlock.Offset, HashIndexBlock](compressedState.bytes)

        uncompressedHashIndex.block.compressionInfo shouldBe empty
        compressedHashIndex.block.compressionInfo shouldBe defined

        uncompressedHashIndex.block.bytesToReadPerIndex shouldBe compressedHashIndex.block.bytesToReadPerIndex
        uncompressedHashIndex.block.hit shouldBe compressedHashIndex.block.hit
        uncompressedHashIndex.block.miss shouldBe compressedHashIndex.block.miss
        uncompressedHashIndex.block.maxProbe shouldBe compressedHashIndex.block.maxProbe
        uncompressedHashIndex.block.writeAbleLargestValueSize shouldBe compressedHashIndex.block.writeAbleLargestValueSize

        //        println(s"hit: ${uncompressedHashIndex.block.hit}")
        //        println(s"miss: ${uncompressedHashIndex.block.miss}")
        //        println(s"prefixCompressionResetCount: ${uncompressedKeyValues.last.sortedIndexConfig.prefixCompressionResetCount}")

        //        val uncompressedBlockReader: UnblockedReader[HashIndexBlock.Offset, HashIndexBlock] = Block.unblock(uncompressedHashIndex, SegmentBlock.unblocked(uncompressedState.bytes), randomBoolean())
        //        val compressedBlockReader = Block.unblock(compressedHashIndex, SegmentBlock.unblocked(compressedState.bytes), randomBoolean())

        //assert that both compressed and uncompressed HashIndexes should result in the same value eventually.
        uncompressedKeyValues foreach {
          keyValue =>
            val uncompressedIndexes = ListBuffer.empty[Int]

            HashIndexBlock.search(
              key = keyValue.key,
              reader = uncompressedHashIndex,
              assertValue =
                index => {
                  uncompressedIndexes += index
                  None
                }
            )

            val compressedIndexes = ListBuffer.empty[Int]
            HashIndexBlock.search(
              key = keyValue.key,
              reader = compressedHashIndex,
              assertValue =
                index => {
                  compressedIndexes += index
                  None
                }
            )

            uncompressedIndexes should contain atLeastOneElementOf compressedIndexes
        }
      }
    }
  }

  "build index" when {
    "the hash is perfect" in {
      runThis(100.times) {
        val maxProbe = 1000
        val startId = Some(0)

        val compressions = randomCompressionsOrEmpty()

        val keyValues =
          randomizedKeyValues(
            count = randomIntMax(10000) max 1,
            startId = startId,
            addPut = true,
            hashIndexConfig =
              HashIndexBlock.Config(
                allocateSpace = _.requiredSpace * 5,
                compressions = _ => compressions,
                maxProbe = maxProbe,
                copyIndex = false,
                minimumNumberOfKeys = 0,
                minimumNumberOfHits = 0,
                ioStrategy = _ => randomIOAccess()
              ),
            sortedIndexConfig =
              SortedIndexBlock.Config.random.copy(prefixCompressionResetCount = 0)
          )

        keyValues should not be empty

        val state =
          HashIndexBlock.init(keyValues = keyValues).value

        val allocatedBytes = state.bytes.allocatedSize

        keyValues foreach {
          keyValue =>
            HashIndexBlock.write(
              key = keyValue.key,
              value = keyValue.stats.thisKeyValuesAccessIndexOffset,
              state = state
            )
        }

        println(s"hit: ${state.hit}")
        println(s"miss: ${state.miss}")
        println

        HashIndexBlock.close(state).value

        println(s"Bytes allocated: $allocatedBytes")
        println(s"Bytes written: ${state.bytes.size}")

        state.hit shouldBe keyValues.size
        state.miss shouldBe 0
        state.hit + state.miss shouldBe keyValues.size

        println("Building ListMap")
        val indexOffsetMap = mutable.HashMap.empty[Int, ListBuffer[Transient]]

        keyValues foreach {
          keyValue =>
            indexOffsetMap.getOrElseUpdate(keyValue.stats.thisKeyValuesAccessIndexOffset, ListBuffer(keyValue)) += keyValue
        }

        println(s"ListMap created with size: ${indexOffsetMap.size}")

        def findKey(indexOffset: Int, key: Slice[Byte]): Option[Transient] =
          indexOffsetMap.get(indexOffset) match {
            case Some(keyValues) =>
              keyValues.find(_.key equiv key)

            case None =>
              fail(s"Got index that does not exist: $indexOffset")
          }

        val hashIndexReader = Block.unblock(BlockRefReader(state.bytes))

        keyValues foreach {
          keyValue =>
            val found =
              HashIndexBlock.search(
                key = keyValue.key,
                reader = hashIndexReader,
                assertValue = findKey(_, keyValue.key)
              ).value

            (found.key equiv keyValue.key) shouldBe true
        }
      }
    }
  }

  "searching a segment" should {
    "value" in {
      runThis(100.times, log = true) {
        //create perfect hash
        val compressions = if (randomBoolean()) randomCompressions() else Seq.empty

        val keyValues =
          randomizedKeyValues(
            count = 1000,
            startId = Some(1),
          ).updateStats(
            hashIndexConfig =
              HashIndexBlock.Config(
                maxProbe = 1000,
                minimumNumberOfKeys = 0,
                minimumNumberOfHits = 0,
                copyIndex = randomBoolean(),
                allocateSpace = _.requiredSpace * 2,
                ioStrategy = _ => randomIOStrategy(),
                compressions = _ => compressions
              ),
            sortedIndexConfig =
              SortedIndexBlock.Config(
                ioStrategy = _ => randomIOStrategy(),
                prefixCompressionResetCount = 0,
                enableAccessPositionIndex = randomBoolean(),
                enablePartialRead = randomBoolean(),
                disableKeyPrefixCompression = randomBoolean(),
                normaliseIndex = randomBoolean(),
                compressions = _ => compressions
              )
          )

        val blocks = getBlocks(keyValues).get
        blocks.hashIndexReader shouldBe defined
        blocks.hashIndexReader.get.block.hit shouldBe keyValues.last.stats.linkedPosition
        blocks.hashIndexReader.get.block.miss shouldBe 0

        keyValues foreach {
          keyValue =>
            HashIndexBlock.search(
              key = keyValue.key,
              hashIndexReader = blocks.hashIndexReader.get,
              sortedIndexReader = blocks.sortedIndexReader,
              valuesReader = blocks.valuesReader
            ) match {
              case _: HashIndexSearchResult.None =>
                fail("None on perfect hash.")

              //                blocks.sortedIndexReader.block.hasPrefixCompression shouldBe (keyValues.last.sortedIndexConfig.prefixCompressionResetCount > 0)
              //
              //                notFound match {
              //                  case HashIndexSearchResult.None =>
              //                    fail("Expected Lower.")
              //
              //                  case HashIndexSearchResult.Lower(lower) =>
              //                    SortedIndexBlock.seekAndMatchOrSeek(
              //                      matcher = KeyMatcher.Get(keyValue.key),
              //                      previous = lower.toPersistent.get,
              //                      next = None,
              //                      fullRead = true,
              //                      indexReader = blocks.sortedIndexReader,
              //                      valuesReader = blocks.valuesReader
              //                    ).value match {
              //                      case Result.Matched(previous, result, next) =>
              //                        result shouldBe keyValue
              //
              //                      case Result.BehindStopped(_) =>
              //                        fail()
              //
              //                      case Result.AheadOrNoneOrEnd =>
              //                        fail()
              //                    }
              //                }

              case HashIndexSearchResult.Some(found) =>
                found shouldBe keyValue
            }
        }
      }
    }
  }
}
