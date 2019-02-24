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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with SwayDB. If not, see <https://www.gnu.org/licenses/>.
 */

package swaydb.core.io.file

import java.nio.file.NoSuchFileException
import scala.concurrent.Future
import scala.concurrent.duration._
import swaydb.core.RunThis._
import swaydb.core.TestData._
import swaydb.core.queue.FileLimiter
import swaydb.core.{TestBase, TestLimitQueues}

class BufferCleanerSpec extends TestBase {

  implicit val fileOpenLimiter: FileLimiter = TestLimitQueues.fileOpenLimiter

  "it should not fatal terminate" when {
    "concurrently reading a deleted MMAP file" in {

      val files =
        (1 to 20) map {
          _ =>
            val file = DBFile.mmapWriteAndRead(randomBytesSlice(), randomDir, autoClose = true).get
            file.delete().get
            file
        }


      //deleting a memory mapped file (that performs unsafe Buffer cleanup)
      //and repeatedly reading from it should not cause fatal shutdown.
      files foreach {
        file =>
          Future {
            while (true)
              file.get(0).failed.get.exception shouldBe a[NoSuchFileException]
          }
      }

      //keep this test running for a few seconds.
      sleep(20.seconds)
    }
  }
}