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

package swaydb.core.segment.format.a

import org.scalatest.OptionValues._
import org.scalatest.PrivateMethodTester
import org.scalatest.concurrent.ScalaFutures
import swaydb.IO
import swaydb.IOValues._
import swaydb.core.CommonAssertions._
import swaydb.core.RunThis._
import swaydb.core.TestBase
import swaydb.core.TestData._
import swaydb.data.slice.Slice
import swaydb.serializers.Default._
import swaydb.serializers._

class SegmentHigherSpec0 extends SegmentHigherSpec {
  val keyValuesCount: Int = 100
}

class SegmentHigherSpec1 extends SegmentHigherSpec {
  val keyValuesCount: Int = 100
  override def levelFoldersCount = 10
  override def mmapSegmentsOnWrite = true
  override def mmapSegmentsOnRead = true
  override def level0MMAP = true
  override def appendixStorageMMAP = true
}

class SegmentHigherSpec2 extends SegmentHigherSpec {
  val keyValuesCount: Int = 100

  override def levelFoldersCount = 10
  override def mmapSegmentsOnWrite = false
  override def mmapSegmentsOnRead = false
  override def level0MMAP = false
  override def appendixStorageMMAP = false
}

class SegmentHigherSpec3 extends SegmentHigherSpec {
  val keyValuesCount: Int = 1000
  override def inMemoryStorage = true
}

sealed trait SegmentHigherSpec extends TestBase with ScalaFutures with PrivateMethodTester {

  def keyValuesCount: Int

  "Segment.higher" should {
    "value the higher key from the segment that has only 1 Remove key" in {
      runThis(50.times) {
        assertSegment(
          keyValues = Slice(randomFixedKeyValue(1)).toTransient,
          assert =
            (keyValue, segment) => {
              IO.Defer(segment.higher(0)).runRandomIO.right.value.value shouldBe keyValue.head
              IO.Defer(segment.higher(1)).runRandomIO.right.value shouldBe empty
              IO.Defer(segment.higher(2)).runRandomIO.right.value shouldBe empty
            }
        )
      }
    }

    "value the higher key from the segment that has only 1 Range key" in {
      runThis(50.times) {
        assertSegment(
          keyValues = Slice(randomRangeKeyValue(1, 10)).toTransient,
          assert =
            (keyValue, segment) => {
              (0 to 9) foreach {
                i =>
                  segment.higher(i).runRandomIO.right.value.value shouldBe keyValue.head
              }

              (10 to 15) foreach {
                i =>
                  segment.higher(i).runRandomIO.right.value shouldBe empty
              }
            }
        )
      }
    }

    "value the higher from the segment when there are no Range key-values" in {
      //1, 2, 3
      assertSegment(
        keyValues = Slice(randomFixedKeyValue(1), randomFixedKeyValue(2), randomFixedKeyValue(3)).toTransient,
        assert =
          (keyValues, segment) => {
            segment.higher(0).runRandomIO.right.value.value shouldBe keyValues(0)
            segment.higher(1).runRandomIO.right.value.value shouldBe keyValues(1)
            segment.higher(2).runRandomIO.right.value.value shouldBe keyValues(2)
            (3 to 10) foreach {
              i =>
                segment.higher(i).runRandomIO.right.value shouldBe empty
            }
          }
      )
    }

    "value the higher from the segment when there are Range key-values" in {
      //  1, (2 - 5), 10, (11 - 20), (20 - 30) (30), (40 - 50)
      runThis(1.times) {
        assertSegment(
          keyValues = Slice(
            randomFixedKeyValue(1),
            randomRangeKeyValue(2, 5),
            randomFixedKeyValue(10),
            randomRangeKeyValue(11, 20),
            randomRangeKeyValue(20, 30)
            //            randomGroup(Slice(randomFixedKeyValue(30), randomRangeKeyValue(40, 50)).toTransient).toMemory
          ).toTransient,
          assert =
            (keyValues, segment) => {
              //0
              //  1, (2 - 5), 10, (11 - 20), (20 - 30) (30), (40 - 50)
              segment.higher(0).runRandomIO.right.value.value shouldBe keyValues(0)
              //1
              //1, (2 - 5), 10, (11 - 20), (20 - 30) (30), (40 - 50)
              segment.higher(1).runRandomIO.right.value.value shouldBe keyValues(1)
              //    2
              //1, (2 - 5), 10, (11 - 20), (20 - 30) (30), (40 - 50)
              segment.higher(2).runRandomIO.right.value.value shouldBe keyValues(1)
              //     3
              //1, (2 - 5), 10, (11 - 20), (20 - 30) (30), (40 - 50)
              segment.higher(3).runRandomIO.right.value.value shouldBe keyValues(1)
              //       4
              //1, (2 - 5), 10, (11 - 20), (20 - 30) (30), (40 - 50)
              segment.higher(4).runRandomIO.right.value.value shouldBe keyValues(1)
              //        5
              //1, (2 - 5), 10, (11 - 20), (20 - 30) (30), (40 - 50)
              segment.higher(5).runRandomIO.right.value.value shouldBe keyValues(2)
              //          6
              //1, (2 - 5), 10, (11 - 20), (20 - 30) (30), (40 - 50)
              segment.higher(6).runRandomIO.right.value.value shouldBe keyValues(2)
              //            10
              //1, (2 - 5), 10, (11 - 20), (20 - 30) (30), (40 - 50)
              segment.higher(10).runRandomIO.right.value.value shouldBe keyValues(3)
              //                 11
              //1, (2 - 5), 10, (11 - 20), (20 - 30) (30), (40 - 50)
              segment.higher(11).runRandomIO.right.value.value shouldBe keyValues(3)
              //                   12
              //1, (2 - 5), 10, (11 - 20), (20 - 30) (30), (40 - 50)
              segment.higher(12).runRandomIO.right.value.value shouldBe keyValues(3)
              //                    19
              //1, (2 - 5), 10, (11 - 20), (20 - 30) (30), (40 - 50)
              segment.higher(19).runRandomIO.right.value.value shouldBe keyValues(3)
              //                      20
              //1, (2 - 5), 10, (11 - 20), (20 - 30) (30), (40 - 50)
              segment.higher(20).runRandomIO.right.value.value shouldBe keyValues(4)
              //                              21
              //1, (2 - 5), 10, (11 - 20), (20 - 30) (30), (40 - 50)
              segment.higher(21).runRandomIO.right.value.value shouldBe keyValues(4)
              //                                29
              //1, (2 - 5), 10, (11 - 20), (20 - 30) (30), (40 - 50)
              segment.higher(29).runRandomIO.right.value.value shouldBe keyValues(4)
              //                                                 50
              //1, (2 - 5), 10, (11 - 20), (20 - 30) (30), (40 - 50)
              segment.higher(50).runRandomIO.right.value shouldBe empty
              //                                                     51
              //1, (2 - 5), 10, (11 - 20), (20 - 30) (30), (40 - 50)
              segment.higher(51).runRandomIO.right.value shouldBe empty
            }
        )
      }
    }

    "random" in {
      assertSegment(
        keyValues = randomizedKeyValues(keyValuesCount),
        assert = assertHigher(_, _)
      )
    }
  }
}
