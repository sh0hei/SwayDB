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

package swaydb.core.util.cache

import org.scalamock.scalatest.MockFactory
import org.scalatest.{Matchers, WordSpec}
import swaydb.core.RunThis._
import swaydb.core.TestData._
import swaydb.data.config.BlockIO
import swaydb.data.{IO, Reserve}

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.Random

class CacheSpec extends WordSpec with Matchers with MockFactory {

  //run all possible combinations of Cache tests on different cache types.
  def runTestForAllCombinations(test: (Boolean, Boolean, Boolean, Boolean) => Unit) = {

    def run(test: (Boolean, Boolean, Boolean) => Unit) = {
      test(true, true, true)
      test(true, true, false)
      test(true, false, true)
      test(true, false, false)

      test(false, true, true)
      test(false, true, false)
      test(false, false, true)
      test(false, false, false)
    }

    run(test(true, _, _, _))
    run(test(false, _, _, _))
  }

  def getBlockIO(isConcurrent: Boolean, isSynchronised: Boolean, isReserved: Boolean, stored: Boolean)(unit: Unit): BlockIO =
    if (isConcurrent)
      BlockIO.ConcurrentIO(cacheOnAccess = stored)
    else if (isSynchronised)
      BlockIO.SynchronisedIO(cacheOnAccess = stored)
    else if (isReserved)
      BlockIO.ReservedIO(cacheOnAccess = stored)
    else
      BlockIO.ConcurrentIO(cacheOnAccess = stored) //then it's concurrent

  /**
    * Return a partial cache with applied configuration which requires the cache body.
    */
  def getTestCache(isBlockIO: Boolean, isConcurrent: Boolean, isSynchronised: Boolean, isReserved: Boolean, stored: Boolean): (Unit => IO[Int]) => Cache[Unit, Int] =
    if (isBlockIO)
      Cache.blockIO[Unit, Int](getBlockIO(isConcurrent, isSynchronised, isReserved, stored), IO.Error.BusyFuture(Reserve()))
    else if (isConcurrent)
      Cache.concurrentIO[Unit, Int](synchronised = false, stored = stored)
    else if (isSynchronised)
      Cache.concurrentIO[Unit, Int](synchronised = true, stored = stored)
    else if (isReserved)
      Cache.reservedIO[Unit, Int](stored = stored, IO.Error.BusyFuture(Reserve()))
    else
      Cache.concurrentIO[Unit, Int](synchronised = false, stored = stored) //then it's concurrent

  "Cache.io" should {
    "fetch data only once on success" in {
      def doTest(isBlockIO: Boolean, isConcurrent: Boolean, isSynchronised: Boolean, isReserved: Boolean) = {
        val mock = mockFunction[IO[Int]]
        val cache = getTestCache(isBlockIO, isConcurrent, isSynchronised, isReserved, stored = true)(_ => mock.apply())

        cache.isCached shouldBe false

        //getOrElse on un-cached should set the cache
        cache.getOrElse(IO(111)) shouldBe IO(111)
        cache.isCached shouldBe false // still not cached
        mock.expects() returning IO(123)

        cache.value() shouldBe IO.Success(123)
        cache.isCached shouldBe true
        cache.value() shouldBe IO.Success(123) //value again mock function is not invoked again

        val mapCache = cache.map(int => IO(int))
        mapCache.value(Int.MaxValue) shouldBe IO.Success(123)
        mapCache.value(???) shouldBe IO.Success(123)

        val flatMapCache = cache.flatMap(Cache.concurrentIO(randomBoolean(), randomBoolean())(int => IO(int + 1)))
        flatMapCache.value(Int.MaxValue) shouldBe IO.Success(124)
        flatMapCache.value(???) shouldBe IO.Success(124)

        //getOrElse on cached is not invoked on new value
        cache.getOrElse(???) shouldBe IO(123)

        cache.clear()
        cache.isCached shouldBe false
      }

      runTestForAllCombinations(doTest)
    }

    "not cache on failure" in {
      def doTest(isBlockIO: Boolean, isConcurrent: Boolean, isSynchronised: Boolean, isReserved: Boolean) = {
        val mock = mockFunction[IO[Int]]

        val cache = getTestCache(isBlockIO, isConcurrent, isSynchronised, isReserved, stored = true)(_ => mock.apply())

        cache.isCached shouldBe false
        mock.expects() returning IO.Failure("Kaboom!") repeat 5.times
        cache.getOrElse(IO(233)) shouldBe IO(233)
        cache.isCached shouldBe false

        //failure
        cache.value().failed.get.exception.getMessage shouldBe "Kaboom!"
        cache.isCached shouldBe false

        val mapCache = cache.map(int => IO(int))
        mapCache.value(Int.MaxValue).failed.get.exception.getMessage shouldBe "Kaboom!"
        mapCache.value(Int.MaxValue).failed.get.exception.getMessage shouldBe "Kaboom!"

        val flatMapCache = cache.flatMap(Cache.concurrentIO(randomBoolean(), randomBoolean())(int => IO(int + 1)))
        flatMapCache.value(Int.MaxValue).failed.get.exception.getMessage shouldBe "Kaboom!"
        flatMapCache.value(Int.MaxValue).failed.get.exception.getMessage shouldBe "Kaboom!"

        //success
        mock.expects() returning IO(123)
        cache.value() shouldBe IO.Success(123) //value again mock function is not invoked again
        mapCache.value() shouldBe IO.Success(123)
        flatMapCache.value() shouldBe IO.Success(124)
        cache.isCached shouldBe true
        cache.isCached shouldBe true
        cache.clear()
        cache.isCached shouldBe false
        cache.getOrElse(IO(233)) shouldBe IO(233)
        cache.isCached shouldBe false
      }

      runTestForAllCombinations(doTest)
    }

    "cache on successful map and flatMap" in {
      def doTest(isBlockIO: Boolean, isConcurrent: Boolean, isSynchronised: Boolean, isReserved: Boolean) = {
        val mock = mockFunction[IO[Int]]

        val cache = getTestCache(isBlockIO, isConcurrent, isSynchronised, isReserved, stored = true)(_ => mock.apply())

        cache.isCached shouldBe false

        mock.expects() returning IO(111)
        cache.map(int => IO(int)).value(12332) shouldBe IO(111)
        cache.flatMap(Cache.concurrentIO(randomBoolean(), true)(int => IO(int + 1))).value(23434) shouldBe IO(112)

        cache.clear()
        cache.isCached shouldBe false
        mock.expects() returning IO(222)
        cache.flatMap(Cache.concurrentIO(randomBoolean(), true)(int => IO(int + 2))).value(43433434) shouldBe IO(224)

        //on cached value ??? is not invoked.
        cache.getOrElse(???) shouldBe IO(222)
      }

      runTestForAllCombinations(doTest)
    }

    "not cache on unsuccessful map and flatMap" in {
      def doTest(isBlockIO: Boolean, isConcurrent: Boolean, isSynchronised: Boolean, isReserved: Boolean) = {
        val mock = mockFunction[IO[Int]]

        val cache = getTestCache(isBlockIO, isConcurrent, isSynchronised, isReserved, stored = true)(_ => mock.apply())

        cache.isCached shouldBe false

        mock.expects() returning IO.Failure("Kaboom!") repeat 2.times
        cache.map(IO(_)).value().failed.get.exception.getMessage shouldBe "Kaboom!"
        cache.isCached shouldBe false
        cache.flatMap(Cache.reservedIO(true, IO.Error.BusyFuture(Reserve()))(int => IO.Success(int + 1))).value().failed.get.exception.getMessage shouldBe "Kaboom!"
        cache.isCached shouldBe false

        mock.expects() returning IO(222)
        cache.flatMap(Cache.concurrentIO(randomBoolean(), true)(int => IO.Success(int + 1))).value() shouldBe IO.Success(223)
        cache.isCached shouldBe true
      }

      runTestForAllCombinations(doTest)
    }

    "clear all flatMapped caches" in {
      val cache = Cache.concurrentIO[Unit, Int](randomBoolean(), true)(_ => IO(1))
      cache.value() shouldBe IO.Success(1)
      cache.isCached shouldBe true

      val nestedCache = Cache.concurrentIO[Int, Int](randomBoolean(), true)(int => IO(int + 1))

      val flatMapCache = cache.flatMap(nestedCache)
      flatMapCache.value() shouldBe IO.Success(2)
      flatMapCache.isCached shouldBe true
      nestedCache.isCached shouldBe true

      val mapCache = flatMapCache.map(int => IO(int + 1))
      mapCache.value() shouldBe IO.Success(3)
      mapCache.isCached shouldBe true

      mapCache.clear()

      cache.isCached shouldBe false
      flatMapCache.isCached shouldBe false
      nestedCache.isCached shouldBe false
      mapCache.isCached shouldBe false
    }

    "store cache value on mapStored" in {
      def doTest(isBlockIO: Boolean, isConcurrent: Boolean, isSynchronised: Boolean, isReserved: Boolean) = {
        val mock = mockFunction[IO[Int]]
        val rootCache = getTestCache(isBlockIO, isConcurrent, isSynchronised, isReserved, stored = false)(_ => mock.apply())
        //ensure rootCache is not stored
        mock.expects() returning IO(1)
        mock.expects() returning IO(2)

        rootCache.isCached shouldBe false
        rootCache.value() shouldBe IO(1)
        rootCache.isCached shouldBe false
        rootCache.value() shouldBe IO(2)
        rootCache.isCached shouldBe false

        mock.expects() returning IO(3)
        //run stored
        val storedCache = rootCache.mapStored {
          previous =>
            previous shouldBe 3
            IO(100)
        }
        storedCache.value() shouldBe IO(100)
        storedCache.isCached shouldBe true
        //rootCache not value is not read
        storedCache.value(???) shouldBe IO(100)
        //original rootCache is still empty
        rootCache.isCached shouldBe false

        //run stored
        val storedCache2 = storedCache.mapStored {
          previous =>
            //stored rootCache's value is received
            previous shouldBe 100
            IO(200)
        }
        storedCache2.value() shouldBe IO(200)
        storedCache2.isCached shouldBe true
        //rootCache not value is not read
        storedCache2.value(???) shouldBe IO(200)
        storedCache.value(???) shouldBe IO(100)
        //original rootCache is still empty
        rootCache.isCached shouldBe false
        storedCache2.isCached shouldBe true
        storedCache.isCached shouldBe true

        //clearing child cache, clears it all.
        storedCache2.clear()
        storedCache2.isCached shouldBe false
        storedCache.isCached shouldBe false
        rootCache.isCached shouldBe false
      }

      runTestForAllCombinations(doTest)
    }

    "concurrent access to reserved io" should {
      "not be allowed" in {
        def doTest(blockIO: Boolean) = {

          @volatile var invokeCount = 0

          val _cache =
            if (blockIO)
              Cache.blockIO[Unit, Int](blockIO = _ => BlockIO.ReservedIO(true), IO.Error.ReservedValue(Reserve())) {
                _ =>
                  invokeCount += 1
                  sleep(5.millisecond) //delay access
                  IO.Success(10)
              }
            else
              Cache.reservedIO[Unit, Int](stored = true, IO.Error.ReservedValue(Reserve())) {
                _ =>
                  invokeCount += 1
                  sleep(5.millisecond) //delay access
                  IO.Success(10)
              }

          val cache =
            if (randomBoolean())
              _cache.map(IO(_))
            else if (randomBoolean())
              _cache.flatMap(Cache.concurrentIO(synchronised = false, stored = false)(IO(_)))
            else
              _cache

          if (blockIO) {
            cache.value()
            cache.clear()
          }

          val futures =
          //concurrently do requests
            Future.sequence {
              (1 to 100) map {
                _ =>
                  Future().flatMap(_ => cache.value().toFuture)
              }
            }

          //results in failure since some thread has reserved.
          val failure = futures.failed.await
          failure shouldBe a[IO.Exception.ReservedValue]

          //eventually it's freed
          eventual {
            failure.asInstanceOf[IO.Exception.ReservedValue].busy.isBusy shouldBe false
          }

          if (blockIO)
            invokeCount shouldBe 2 //since it's cleared above.
          else
            invokeCount shouldBe 1
        }

        doTest(blockIO = true)
        doTest(blockIO = false)
      }
    }
  }

  "Cache.value" should {
    "cache function's output" in {
      def doTest(isSynchronised: Boolean) = {
        @volatile var got1 = false
        val cache =
          Cache.noIO[Unit, Int](synchronised = Random.nextBoolean(), stored = true) {
            _ =>
              if (!got1) {
                got1 = true
                1
              } else {
                Random.nextInt()
              }
          }

        cache.isCached shouldBe false

        cache.value() shouldBe 1

        (1 to 1000).par foreach {
          _ =>
            cache.value() shouldBe 1
        }

        cache.isCached shouldBe true

        cache.clear()
        cache.isCached shouldBe false

        cache.value() should not be 1
        cache.isCached shouldBe true
      }

      doTest(isSynchronised = true)
      doTest(isSynchronised = false)
    }
  }
}
