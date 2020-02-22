/*
 * Copyright (c) 2020 Simer JS Plaha (simer.j@gmail.com - @simerplaha)
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

package swaydb.core.util.series

import java.util.concurrent.atomic.AtomicReferenceArray

class SeriesAtomic[T](array: AtomicReferenceArray[T]) extends Series[T] {
  override def getOrNull(index: Int): T =
    array.get(index)

  override def set(index: Int, item: T): Unit =
    array.set(index, item)

  override def length: Int =
    array.length()

  override def iterator: Iterator[T] =
    new Iterator[T] {
      val innerIterator = (0 until array.length()).iterator

      override def hasNext: Boolean =
        innerIterator.hasNext

      override def next(): T =
        array.get(innerIterator.next())
    }

  override def isConcurrent: Boolean =
    true
}