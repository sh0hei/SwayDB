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

import swaydb.core.data.KeyValue.ReadOnly
import swaydb.core.data.{Memory, Value}
import swaydb.core.function.FunctionStore
import swaydb.data.order.TimeOrder
import swaydb.data.slice.Slice

private[core] object UpdateMerger {

  def apply(newKeyValue: ReadOnly.Update,
            oldKeyValue: ReadOnly.Put)(implicit timeOrder: TimeOrder[Slice[Byte]]): ReadOnly.Fixed =
    if (newKeyValue.time > oldKeyValue.time)
      (newKeyValue.deadline, oldKeyValue.deadline) match {
        case (None, None) =>
          newKeyValue.toPut()

        case (Some(_), None) =>
          newKeyValue.toPut()

        case (None, Some(_)) =>
          newKeyValue.toPut(oldKeyValue.deadline)

        case (Some(_), Some(_)) =>
          newKeyValue.toPut()
      }
    else
      oldKeyValue

  def apply(newKeyValue: ReadOnly.Update,
            oldKeyValue: ReadOnly.Remove)(implicit timeOrder: TimeOrder[Slice[Byte]]): ReadOnly.Fixed =
    if (newKeyValue.time > oldKeyValue.time)
      (newKeyValue.deadline, oldKeyValue.deadline) match {
        case (None, None) =>
          oldKeyValue.copyWithTime(newKeyValue.time)

        case (Some(_), None) =>
          oldKeyValue.copyWithTime(newKeyValue.time)

        case (None, Some(_)) =>
          newKeyValue.copyWithDeadline(oldKeyValue.deadline)

        case (Some(_), Some(_)) =>
          newKeyValue
      }
    else
      oldKeyValue

  def apply(newKeyValue: ReadOnly.Update,
            oldKeyValue: ReadOnly.Update)(implicit timeOrder: TimeOrder[Slice[Byte]]): ReadOnly.Update =
    if (newKeyValue.time > oldKeyValue.time)
      (newKeyValue.deadline, oldKeyValue.deadline) match {
        case (None, None) =>
          newKeyValue

        case (Some(_), None) =>
          newKeyValue

        case (None, Some(_)) =>
          newKeyValue.copyWithDeadline(oldKeyValue.deadline)

        case (Some(_), Some(_)) =>
          newKeyValue
      }
    else
      oldKeyValue

  def apply(newKeyValue: ReadOnly.Update,
            oldKeyValue: ReadOnly.Function)(implicit timeOrder: TimeOrder[Slice[Byte]],
                                            functionStore: FunctionStore): ReadOnly.Fixed =
    if (newKeyValue.time > oldKeyValue.time) {
      val oldValue = oldKeyValue.toFromValue()
      val newValue = newKeyValue.toFromValue()
      Memory.PendingApply(newKeyValue.key, Slice(oldValue, newValue))
    }
    else
      oldKeyValue

  def apply(newKeyValue: ReadOnly.Update,
            oldKeyValue: Value.Apply)(implicit timeOrder: TimeOrder[Slice[Byte]],
                                      functionStore: FunctionStore): ReadOnly.Fixed =
    if (newKeyValue.time > oldKeyValue.time)
      oldKeyValue match {
        case oldKeyValue: Value.Remove =>
          UpdateMerger(newKeyValue, oldKeyValue.toMemory(newKeyValue.key))

        case oldKeyValue: Value.Update =>
          UpdateMerger(newKeyValue, oldKeyValue.toMemory(newKeyValue.key))

        case oldKeyValue: Value.Function =>
          UpdateMerger(newKeyValue, oldKeyValue.toMemory(newKeyValue.key))
      }
    else
      oldKeyValue.toMemory(newKeyValue.key)

  def apply(newKeyValue: ReadOnly.Update,
            oldKeyValue: ReadOnly.PendingApply)(implicit timeOrder: TimeOrder[Slice[Byte]],
                                                functionStore: FunctionStore): ReadOnly.Fixed =
    if (newKeyValue.time > oldKeyValue.time)
      FixedMerger(
        newer = newKeyValue,
        oldApplies = oldKeyValue.getOrFetchApplies
      )
    else
      oldKeyValue

  def apply(newKeyValue: ReadOnly.Update,
            oldKeyValue: ReadOnly.Fixed)(implicit timeOrder: TimeOrder[Slice[Byte]],
                                         functionStore: FunctionStore): ReadOnly.Fixed =
  //@formatter:off
    oldKeyValue match {
      case oldKeyValue: ReadOnly.Put =>             UpdateMerger(newKeyValue, oldKeyValue)
      case oldKeyValue: ReadOnly.Remove =>          UpdateMerger(newKeyValue, oldKeyValue)
      case oldKeyValue: ReadOnly.Update =>          UpdateMerger(newKeyValue, oldKeyValue)
      case oldKeyValue: ReadOnly.Function =>        UpdateMerger(newKeyValue, oldKeyValue)
      case oldKeyValue: ReadOnly.PendingApply =>    UpdateMerger(newKeyValue, oldKeyValue)
    }
  //@formatter:on

}
