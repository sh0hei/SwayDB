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

private[core] object RemoveMerger {

  def apply(newKeyValue: ReadOnly.Remove,
            oldKeyValue: ReadOnly.Remove)(implicit timeOrder: TimeOrder[Slice[Byte]]): ReadOnly.Remove =
    if (newKeyValue.time > oldKeyValue.time)
      (newKeyValue.deadline, oldKeyValue.deadline) match {
        case (None, _) =>
          newKeyValue

        case (Some(_), None) =>
          oldKeyValue.copyWithTime(time = newKeyValue.time)

        case (Some(_), Some(_)) =>
          newKeyValue
      }
    else
      oldKeyValue

  def apply(newKeyValue: ReadOnly.Remove,
            oldKeyValue: ReadOnly.Put)(implicit timeOrder: TimeOrder[Slice[Byte]]): ReadOnly.Fixed =
    if (newKeyValue.time > oldKeyValue.time)
      newKeyValue.deadline match {
        case Some(_) =>
          oldKeyValue.copyWithDeadlineAndTime(newKeyValue.deadline, newKeyValue.time)

        case None =>
          newKeyValue
      }
    else
      oldKeyValue

  def apply(newKeyValue: ReadOnly.Remove,
            oldKeyValue: ReadOnly.Update)(implicit timeOrder: TimeOrder[Slice[Byte]]): ReadOnly.Fixed =
    if (newKeyValue.time > oldKeyValue.time)
      newKeyValue.deadline match {
        case Some(_) =>
          oldKeyValue.copyWithDeadlineAndTime(newKeyValue.deadline, newKeyValue.time)

        case None =>
          newKeyValue
      }
    else
      oldKeyValue

  def apply(newKeyValue: ReadOnly.Remove,
            oldKeyValue: ReadOnly.Function)(implicit timeOrder: TimeOrder[Slice[Byte]]): ReadOnly.Fixed =
    if (newKeyValue.time > oldKeyValue.time)
      newKeyValue.deadline match {
        case None =>
          newKeyValue

        case Some(_) =>
          Memory.PendingApply(
            key = newKeyValue.key,
            applies = Slice(oldKeyValue.toFromValue(), newKeyValue.toRemoveValue())
          )
      }
    else
      oldKeyValue

  def apply(newKeyValue: ReadOnly.Remove,
            oldKeyValue: Value.Apply)(implicit timeOrder: TimeOrder[Slice[Byte]]): ReadOnly.Fixed =
    if (newKeyValue.time > oldKeyValue.time)
      oldKeyValue match {
        case oldKeyValue: Value.Remove =>
          RemoveMerger(newKeyValue, oldKeyValue.toMemory(newKeyValue.key))

        case oldKeyValue: Value.Update =>
          RemoveMerger(newKeyValue, oldKeyValue.toMemory(newKeyValue.key))

        case oldKeyValue: Value.Function =>
          RemoveMerger(newKeyValue, oldKeyValue.toMemory(newKeyValue.key))
      }
    else
      oldKeyValue.toMemory(newKeyValue.key)

  def apply(newer: ReadOnly.Remove,
            older: ReadOnly.PendingApply)(implicit timeOrder: TimeOrder[Slice[Byte]],
                                          functionStore: FunctionStore): ReadOnly.Fixed =
    if (newer.time > older.time)
      newer.deadline match {
        case Some(_) =>
          FixedMerger(
            newer = newer,
            oldApplies = older.getOrFetchApplies
          )

        case None =>
          newer
      }
    else
      older

  def apply(newKeyValue: ReadOnly.Remove,
            oldKeyValue: ReadOnly.Fixed)(implicit timeOrder: TimeOrder[Slice[Byte]],
                                         functionStore: FunctionStore): ReadOnly.Fixed =
  //@formatter:off
    oldKeyValue match {
      case oldKeyValue: ReadOnly.Put =>             RemoveMerger(newKeyValue, oldKeyValue)
      case oldKeyValue: ReadOnly.Remove =>          RemoveMerger(newKeyValue, oldKeyValue)
      case oldKeyValue: ReadOnly.Update =>          RemoveMerger(newKeyValue, oldKeyValue)
      case oldKeyValue: ReadOnly.Function =>        RemoveMerger(newKeyValue, oldKeyValue)
      case oldKeyValue: ReadOnly.PendingApply =>    RemoveMerger(newKeyValue, oldKeyValue)
    }
  //@formatter:on

}
