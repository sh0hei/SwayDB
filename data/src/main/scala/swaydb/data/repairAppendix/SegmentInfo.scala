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

package swaydb.data.repairAppendix

import java.nio.file.Path

import swaydb.data.MaxKey
import swaydb.data.slice.Slice

private[swaydb] case class SegmentInfoUnTyped(path: Path,
                                              minKey: Slice[Byte],
                                              maxKey: MaxKey[Slice[Byte]],
                                              segmentSize: Int,
                                              keyValueCount: Int)

case class SegmentInfo[K](path: Path,
                          minKey: K,
                          maxKey: MaxKey[K],
                          segmentSize: Int,
                          keyValueCount: Int)
