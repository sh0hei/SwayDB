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

package swaydb.core.util

import org.scalatest.{Matchers, WordSpec}
import swaydb.core.util.Times._
import swaydb.data.slice.Slice

import scala.concurrent.duration._

class TimesSpec extends WordSpec with Matchers {

  "toNanos" should {
    "convert deadline to nanos" in {
      val duration = 10.seconds
      val deadline = Deadline(duration)
      deadline.toNanos shouldBe duration.toNanos

      Some(deadline).toNanos shouldBe duration.toNanos
    }

    "convert none deadline to 0" in {
      Option.empty[Deadline].toNanos shouldBe 0L
    }
  }

  "toBytes" should {
    "convert deadline long bytes" in {
      val duration = 10.seconds
      val deadline = Deadline(duration)
      deadline.toUnsignedBytes shouldBe Slice.writeUnsignedLong(duration.toNanos)
      deadline.toBytes shouldBe Slice.writeLong(duration.toNanos)
    }
  }

  "toDeadline" should {
    "convert long to deadline" in {
      val duration = 10.seconds
      duration.toNanos.toDeadlineOption should contain(Deadline(duration))
    }

    "convert 0 to None" in {
      0L.toDeadlineOption shouldBe empty
    }
  }
}
