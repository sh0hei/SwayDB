package swaydb.core.merge

import scala.util.{Success, Try}
import swaydb.core.data.KeyValue.ReadOnly
import swaydb.core.data.{Memory, Value}
import swaydb.core.function.FunctionStore
import swaydb.data.order.TimeOrder
import swaydb.data.slice.Slice

object RemoveMerger {

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
            oldKeyValue: ReadOnly.Function)(implicit timeOrder: TimeOrder[Slice[Byte]]): Try[ReadOnly.Fixed] =
    if (newKeyValue.time > oldKeyValue.time)
      newKeyValue.deadline match {
        case None =>
          Success(newKeyValue)

        case Some(_) =>
          oldKeyValue.toFromValue() map {
            oldValue =>
              Memory.PendingApply(newKeyValue.key, Slice(oldValue, newKeyValue.toRemoveValue()))
          }
      }
    else
      Success(oldKeyValue)

  def apply(newKeyValue: ReadOnly.Remove,
            oldKeyValue: Value.Apply)(implicit timeOrder: TimeOrder[Slice[Byte]]): Try[ReadOnly.Fixed] =
    if (newKeyValue.time > oldKeyValue.time)
      oldKeyValue match {
        case oldKeyValue: Value.Remove =>
          Try(RemoveMerger(newKeyValue, oldKeyValue.toMemory(newKeyValue.key)))

        case oldKeyValue: Value.Update =>
          Try(RemoveMerger(newKeyValue, oldKeyValue.toMemory(newKeyValue.key)))

        case oldKeyValue: Value.Function =>
          RemoveMerger(newKeyValue, oldKeyValue.toMemory(newKeyValue.key))
      }
    else
      Try(oldKeyValue.toMemory(newKeyValue.key))

  def apply(newer: ReadOnly.Remove,
            older: ReadOnly.PendingApply)(implicit timeOrder: TimeOrder[Slice[Byte]],
                                          functionStore: FunctionStore): Try[ReadOnly.Fixed] =
    if (newer.time > older.time)
      newer.deadline match {
        case Some(_) =>
          older.getOrFetchApplies flatMap {
            olderApplies =>
              FixedMerger(
                newer = newer,
                oldApplies = olderApplies
              )
          }

        case None =>
          Success(newer)
      }
    else
      Success(older)

  def apply(newKeyValue: ReadOnly.Remove,
            oldKeyValue: ReadOnly.Fixed)(implicit timeOrder: TimeOrder[Slice[Byte]],
                                         functionStore: FunctionStore): Try[ReadOnly.Fixed] =
  //@formatter:off
    oldKeyValue match {
      case oldKeyValue: ReadOnly.Put =>             Try(RemoveMerger(newKeyValue, oldKeyValue))
      case oldKeyValue: ReadOnly.Remove =>          Try(RemoveMerger(newKeyValue, oldKeyValue))
      case oldKeyValue: ReadOnly.Update =>          Try(RemoveMerger(newKeyValue, oldKeyValue))
      case oldKeyValue: ReadOnly.Function =>        RemoveMerger(newKeyValue, oldKeyValue)
      case oldKeyValue: ReadOnly.PendingApply =>    RemoveMerger(newKeyValue, oldKeyValue)
    }
  //@formatter:on

}
