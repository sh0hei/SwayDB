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

import swaydb.IO
import swaydb.core.data.Persistent

sealed trait HashIndexSearchResult {
  def matched: Option[Persistent.Partial]
}

object HashIndexSearchResult {

  val none = None(scala.None, scala.None)

  val noneIO = IO.Right[Nothing, HashIndexSearchResult.None](None(scala.None, scala.None))(IO.ExceptionHandler.Nothing)

  case class None(lower: Option[Persistent.Partial], higher: Option[Persistent.Partial]) extends HashIndexSearchResult {
    def matched: Option[Persistent.Partial] = scala.None
  }

  case class Some(keyValue: Persistent.Partial) extends HashIndexSearchResult {
    def matched: Option[Persistent.Partial] = scala.Some(keyValue)
  }
}
