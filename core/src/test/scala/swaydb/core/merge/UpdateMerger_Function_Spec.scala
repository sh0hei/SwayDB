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

package swaydb.core.merge

import org.scalatest.{Matchers, WordSpec}
import swaydb.IOValues._
import swaydb.core.CommonAssertions._
import swaydb.core.RunThis._
import swaydb.core.TestData._
import swaydb.core.TestTimer
import swaydb.core.data.Memory
import swaydb.data.order.{KeyOrder, TimeOrder}
import swaydb.data.slice.Slice
import swaydb.serializers.Default._
import swaydb.serializers._

class UpdateMerger_Function_Spec extends WordSpec with Matchers {

  implicit val keyOrder = KeyOrder.default
  implicit val timeOrder: TimeOrder[Slice[Byte]] = TimeOrder.long
  "Merging update into Function" when {
    "times are in order" in {

      implicit val testTimer = TestTimer.Incremental()

      runThis(1000.times) {
        val key = randomStringOption

        val output = randomFunctionOutput()

        val oldKeyValue = randomFunctionKeyValue(key = key, output)(eitherOne(testTimer, TestTimer.Empty))

        val newKeyValue = randomUpdateKeyValue(key = key)(eitherOne(testTimer, TestTimer.Empty))

        //          println(s"oldKeyValue: $oldKeyValue")
        //          println(s"newKeyValue: $newKeyValue")

        val expected = Memory.PendingApply(key = key, Slice(oldKeyValue.toFromValue().runRandomIO.right.value, newKeyValue.toFromValue().runRandomIO.right.value))

        assertMerge(
          newKeyValue = newKeyValue,
          oldKeyValue = oldKeyValue,
          expected = expected,
          lastLevel = expected.toLastLevelExpected
        )
      }
    }
  }
}
