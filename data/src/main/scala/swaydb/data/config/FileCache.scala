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

package swaydb.data.config

import swaydb.Tagged

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

sealed trait FileCache extends Tagged[FileCache.Enable, Option]

object FileCache {

  case object Disable extends FileCache {
    override def get: Option[Enable] = None
  }

  object Enable {
    def default(maxOpen: Int,
                interval: FiniteDuration,
                ec: ExecutionContext) =
      FileCache.Enable(
        maxOpen = maxOpen,
        actorConfig = ActorConfig.TimeLoop(
          delay = interval,
          ec = ec
        )
      )
  }

  case class Enable(maxOpen: Int,
                    actorConfig: ActorConfig) extends FileCache {
    override def get: Option[Enable] = Some(this)
  }
}
