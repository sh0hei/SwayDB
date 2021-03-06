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

package swaydb.core.segment.format.a.block

import org.scalatest.OptionValues._
import swaydb.IOValues._
import swaydb.core.CommonAssertions._
import swaydb.core.RunThis._
import swaydb.core.TestData._
import swaydb.core.data.Value.{FromValue, RangeValue}
import swaydb.core.data._
import swaydb.core.io.reader.Reader
import swaydb.core.{TestBase, TestSweeper, TestTimer}
import swaydb.data.config.IOStrategy
import swaydb.data.order.KeyOrder
import swaydb.data.slice.Slice
import swaydb.serializers.Default._
import swaydb.serializers._

class SegmentBlockSpec extends TestBase {

  val keyValueCount = 100

  implicit val keyOrder: KeyOrder[Slice[Byte]] = KeyOrder.default
  implicit val memorySweeper = TestSweeper.memorySweeperMax

  implicit def testTimer: TestTimer = TestTimer.random
  implicit def segmentIO: SegmentIO = SegmentIO.random

  "SegmentBlock" should {
    "convert empty KeyValues and not throw exception but return empty bytes" in {
      val closedSegment =
        SegmentBlock.writeClosed(
          keyValues = Seq.empty,
          segmentConfig = SegmentBlock.Config.random,
          createdInLevel = randomIntMax()
        ).runRandomIO.right.value

      closedSegment.segmentBytes.isEmpty shouldBe true
      closedSegment.nearestDeadline shouldBe empty
    }

    "converting KeyValues to bytes and execute readAll and find on the bytes" in {
      def test(keyValues: Slice[Transient]) = {
        val closedSegment =
          SegmentBlock.writeClosed(
            keyValues = keyValues,
            segmentConfig =
              new SegmentBlock.Config(
                blockIO = _ => randomIOStrategy(),
                compressions = _ => Seq.empty
              ),
            createdInLevel = randomNextInt(Int.MaxValue),
          )

        val reader = Reader(closedSegment.flattenSegmentBytes)
        assertReads(keyValues, reader)
        val persistentReader = createRandomFileReader(closedSegment.flattenSegmentBytes)
        assertReads(keyValues, persistentReader)
      }

      runThis(100.times, log = true) {
        val count = eitherOne(randomIntMax(20) max 1, 50, 100)
        val keyValues = randomizedKeyValues(count, startId = Some(1))
        if (keyValues.nonEmpty) test(keyValues)
      }
    }

    "converting large KeyValues to bytes" in {
      runThis(10.times, log = true) {
        //increase the size of value to test it on larger values.
        val keyValues =
          randomPutKeyValues(count = 100, valueSize = 10000, startId = Some(0)).toTransient

        val bytes =
          SegmentBlock.writeClosed(
            keyValues = keyValues,
            segmentConfig =
              new SegmentBlock.Config(
                blockIO = dataType => IOStrategy.ConcurrentIO(cacheOnAccess = dataType.isCompressed),
                compressions = _ => Seq.empty
              ),
            createdInLevel = 0
          ).flattenSegmentBytes

        //in memory
        assertReads(keyValues, Reader(bytes.unslice()))
        //on disk
        assertReads(keyValues, createRandomFileReader(bytes))
      }
    }

    "write and read Int min max key values" in {
      val keyValues = Slice(Transient.put(Int.MaxValue, Int.MinValue), Transient.put(Int.MinValue, Int.MaxValue)).updateStats

      val (bytes, deadline) =
        SegmentBlock.writeClosed(
          keyValues = keyValues,
          segmentConfig =
            new SegmentBlock.Config(
              blockIO = dataType => IOStrategy.ConcurrentIO(cacheOnAccess = dataType.isCompressed),
              compressions = _ => Seq.empty
            ),
          createdInLevel = 0
        ).flattenSegment

      deadline shouldBe empty

      //in memory
      assertReads(keyValues, Reader(bytes))
      //on disk
      assertReads(keyValues, createRandomFileReader(bytes))
    }

    "write and read Keys with None value to a Slice[Byte]" in {
      val setDeadlines = false
      val keyValues = randomFixedNoneValue(count = randomIntMax(1000) max 1, startId = Some(1), addPutDeadlines = setDeadlines, addUpdateDeadlines = setDeadlines, addRemoveDeadlines = setDeadlines)

      keyValues foreach {
        keyValue =>
          keyValue.valueEntryBytes shouldBe empty
      }

      val (bytes, deadline) =
        SegmentBlock.writeClosed(
          keyValues = keyValues,
          segmentConfig =
            new SegmentBlock.Config(
              blockIO = dataType => IOStrategy.ConcurrentIO(cacheOnAccess = dataType.isCompressed),
              compressions = _ => Seq.empty
            ),
          createdInLevel = 0
        ).flattenSegment

      if (!setDeadlines) deadline shouldBe empty

      //in memory
      assertReads(keyValues, Reader(bytes))
      //on disk
      assertReads(keyValues, createRandomFileReader(bytes))
    }

    "report Segment corruption if CRC check does not match when reading the footer" in {
      //FIXME - flaky tests

      //      runThis(100.times) {
      //        val keyValues = Slice(Transient.put(1)).updateStats
      //
      //        val (bytes, _) =
      //          SegmentBlock.write(
      //            keyValues = keyValues,
      //            segmentConfig =
      //      SegmentBlock.Config(
      //        blockIO = dataType => BlockIO.SynchronisedIO(cacheOnAccess = dataType.isCompressed),
      //        compressions = Seq.empty
      //      ),
      //            createdInLevel = 0
      //          ).runIO.flattenSegment
      //
      //        //        val result = SegmentBlock.read(SegmentBlock.Offset(0, bytes.size), Reader(bytes.drop(2)))
      //        //        if(result.isSuccess)
      //        //          println("debug")
      //        //        result.failed.runIO.exception shouldBe a[SegmentCorruptionException]
      //
      //        SegmentBlock.read(SegmentBlock.Offset(0, bytes.size), Reader(bytes)) map {
      //          segmentBlock =>
      //            SegmentBlock.readFooter(segmentBlock.createBlockReader(Reader(bytes.drop(1)))).failed.runIO.exception shouldBe a[SegmentCorruptionException]
      //            SegmentBlock.readFooter(segmentBlock.createBlockReader(Reader(bytes.dropRight(1)))).failed.runIO.exception shouldBe a[SegmentCorruptionException]
      //            SegmentBlock.readFooter(segmentBlock.createBlockReader(Reader(bytes.slice(10, 20)))).failed.runIO.exception shouldBe a[SegmentCorruptionException]
      //        } get
      //      }
    }
  }

  "SegmentFooter.read" should {
    "set hasRange to false when Segment contains no Range key-value" in {
      runThis(100.times) {
        val keyValues = randomizedKeyValues(keyValueCount, addRanges = false)
        if (keyValues.nonEmpty) {

          val blocks = getBlocks(keyValues).get

          blocks.footer.keyValueCount shouldBe keyValues.size
          blocks.footer.hasRange shouldBe false
        }
      }
    }

    "set hasRange to true and hasRemoveRange to false when Segment does not contain Remove range or function or pendingApply with function or remove but has other ranges" in {
      def doAssert(keyValues: Slice[Transient]) = {
        val expectedHasRemoveRange =
          keyValues.exists {
            case _: Transient.Remove => true
            case _: Transient.Put => false
            case _: Transient.Update => false
            case _: Transient.Function => true
            case range: Transient.Range =>
              range.rangeValue match {
                case _: Value.Remove => true
                case _: Value.Update => false
                case _: Value.Function => true
                case Value.PendingApply(applies) =>
                  applies exists {
                    case _: Value.Remove => true
                    case _: Value.Update => false
                    case _: Value.Function => true
                  }
              }
            case apply: Transient.PendingApply =>
              apply.applies exists {
                case _: Value.Remove => true
                case _: Value.Update => false
                case _: Value.Function => true
              }
          }

        keyValues.last.stats.segmentHasRemoveRange shouldBe expectedHasRemoveRange

        val blocks = getBlocks(keyValues).get

        blocks.footer.keyValueCount shouldBe keyValues.size
        blocks.footer.keyValueCount shouldBe keyValues.size
        blocks.footer.hasRange shouldBe true
      }

      runThis(100.times) {
        doAssert(randomizedKeyValues(keyValueCount, addRangeRemoves = false, addRanges = true, startId = Some(1)))
      }
    }

    "set hasRange & hasRemoveRange to true and not create bloomFilter when Segment contains Remove range key-value" in {
      def doAssert(keyValues: Slice[Transient]) = {
        keyValues.last.stats.segmentHasRemoveRange shouldBe true

        val blocks = getBlocks(keyValues).get

        blocks.footer.keyValueCount shouldBe keyValues.size
        blocks.footer.keyValueCount shouldBe keyValues.size
        blocks.footer.hasRange shouldBe true
        //bloom filters do
        blocks.footer.bloomFilterOffset shouldBe empty
      }

      runThis(100.times) {
        val keyValues =
          randomizedKeyValues(keyValueCount, startId = Some(1)) ++
            Seq(
              Transient.Range.create[FromValue, RangeValue](
                fromKey = 20,
                toKey = 21,
                fromValue = randomFromValueOption(),
                rangeValue = Value.remove(randomDeadlineOption)
              )
            )

        doAssert(keyValues.updateStats)
      }
    }

    "set hasRange to false when there are no ranges" in {
      def doAssert(_keyValues: Slice[Transient]) = {
        _keyValues.last.stats.segmentHasRemoveRange shouldBe false

        _keyValues.last.stats.segmentHasRemoveRange shouldBe false

        val keyValues =
          _keyValues.updateStats(
            bloomFilterConfig =
              BloomFilterBlock.Config.random.copy(
                falsePositiveRate = 0.0001,
                minimumNumberOfKeys = 0
              )
          )

        val blocks = getBlocks(keyValues).get

        blocks.footer.keyValueCount shouldBe keyValues.size
        blocks.footer.keyValueCount shouldBe keyValues.size
        blocks.footer.numberOfRanges shouldBe keyValues.last.stats.segmentTotalNumberOfRanges
        blocks.bloomFilterReader shouldBe defined
        blocks.footer.bloomFilterOffset shouldBe defined
        blocks.bloomFilterReader shouldBe defined
        assertBloom(keyValues, blocks.bloomFilterReader.get)
      }

      runThis(100.times) {
        val keyValues = randomizedKeyValues(keyValueCount, addRanges = false, addRangeRemoves = false)
        if (keyValues.nonEmpty) doAssert(keyValues)
      }
    }

    "set hasRemoveRange to true, hasGroup to true & not create bloomFilter when only the group contains remove range" in {
      def doAssert(keyValues: Slice[Transient]) = {
        keyValues.last.stats.segmentHasRemoveRange shouldBe true

        val blocks = getBlocks(keyValues).get

        blocks.footer.keyValueCount shouldBe keyValues.size
        blocks.footer.hasRange shouldBe true
        blocks.footer.bloomFilterOffset shouldBe empty
        blocks.bloomFilterReader shouldBe empty
      }

      runThis(100.times) {
        doAssert(
          Slice(
            randomFixedKeyValue(1).toTransient,
            randomFixedKeyValue(2).toTransient,
            randomPutKeyValue(10, Some("val")).toTransient,
            randomRangeKeyValue(12, 15, rangeValue = Value.remove(None)).toTransient
          ).updateStats(
            bloomFilterConfig =
              BloomFilterBlock.Config.random.copy(
                falsePositiveRate = 0.0001,
                minimumNumberOfKeys = 0
              )
          )
        )
      }
    }
  }

  "writing key-values with duplicate values" should {
    "use the same valueOffset and not create duplicate values" in {
      runThis(1000.times) {
        //make sure the first byte in the value is not the same as the key (just for the this test).
        val fixedValue: Slice[Byte] = Slice(11.toByte) ++ randomBytesSlice(randomIntMax(50)).drop(1)

        def fixed =
          Seq(
            Memory.put(1, fixedValue),
            Memory.update(2, fixedValue),
            Memory.put(3, fixedValue),
            Memory.put(4, fixedValue),
            Memory.update(5, fixedValue),
            Memory.put(6, fixedValue),
            Memory.update(7, fixedValue),
            Memory.update(8, fixedValue),
            Memory.put(9, fixedValue),
            Memory.update(10, fixedValue)
          ).toTransient

        val applies = randomApplies(deadline = None)

        def pendingApply: Slice[Transient] =
          Seq(
            Memory.PendingApply(1, applies),
            Memory.PendingApply(2, applies),
            Memory.PendingApply(3, applies),
            Memory.PendingApply(4, applies),
            Memory.PendingApply(5, applies),
            Memory.PendingApply(6, applies),
            Memory.PendingApply(7, applies)
          ).toTransient

        val keyValues =
          eitherOne(
            left = fixed,
            right = pendingApply
          ).updateStats(
            valuesConfig =
              ValuesBlock.Config(
                compressDuplicateValues = true,
                compressDuplicateRangeValues = randomBoolean(),
                ioStrategy = _ => randomIOAccess(),
                compressions = _ => Seq.empty
              )
          )

        //value the first value for either fixed or range.
        //this value is only expected to be written ones.
        keyValues.head.valueEntryBytes shouldBe defined
        val value = keyValues.head.valueEntryBytes.value

        val blocks = getBlocks(keyValues).get

        val bytes = blocks.valuesReader.value.readAllAndGetReader().readRemaining()

        //only the bytes of the first value should be set and the next byte should be the start of index
        //as values are not duplicated
        bytes.take(value.size) shouldBe value
        //drop the first value bytes that are value bytes and the next value bytes (value of the next key-value) should not be value bytes.
        bytes.drop(value.size).take(value.size) should not be value

        val readKeyValues = SortedIndexBlock.readAll(blocks.footer.keyValueCount, blocks.sortedIndexReader, blocks.valuesReader)
        readKeyValues should have size keyValues.size

        //assert that all valueOffsets of all key-values are the same
        readKeyValues.foldLeft(Option.empty[Int]) {
          case (previousOffsetOption, fixed: Persistent.Fixed) =>
            previousOffsetOption match {
              case Some(previousOffset) =>
                fixed.valueOffset shouldBe previousOffset
                fixed.valueLength shouldBe value.size
                previousOffsetOption

              case None =>
                Some(fixed.valueOffset)
            }

          case keyValue =>
            fail(s"Got: ${keyValue.getClass.getSimpleName}. Didn't expect any other key-value other than Put")
        }
      }
    }
  }
}
