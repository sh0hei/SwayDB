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

package swaydb.core.level.seek

import swaydb.core.data.KeyValue

private[swaydb] object Seek {

  sealed trait Current
  object Current {
    case class Read(previousSegmentId: Long) extends Seek.Current
    case object Stop extends Seek.Current
    case class Stash(segmentId: Long, current: KeyValue.ReadOnly) extends Seek.Current
  }

  sealed trait Next
  object Next {
    case object Read extends Seek.Next
    case object Stop extends Seek.Next
    case class Stash(next: KeyValue.ReadOnly.Put) extends Seek.Next
  }
}
