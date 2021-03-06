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

import swaydb.IO
import swaydb.core.data.KeyValue
import swaydb.core.level.LevelSeek
import swaydb.core.segment.ReadState
import swaydb.data.slice.Slice

trait CurrentWalker {

  def levelNumber: String

  def get(key: Slice[Byte], readState: ReadState): IO.Defer[swaydb.Error.Level, Option[KeyValue.ReadOnly.Put]]

  def higher(key: Slice[Byte], readState: ReadState): LevelSeek[KeyValue.ReadOnly]

  def lower(key: Slice[Byte], readState: ReadState): LevelSeek[KeyValue.ReadOnly]
}
