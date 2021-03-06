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

package swaydb.core.cache

import com.typesafe.scalalogging.LazyLogging
import swaydb.IO
import swaydb.core.segment.format.a.block.ValuesBlock
import swaydb.core.segment.format.a.block.reader.UnblockedReader
import swaydb.data.Reserve
import swaydb.data.config.IOStrategy
import swaydb.data.util.Functions

private[core] object Cache {

  def valueIO[E: IO.ExceptionHandler, I, B](output: B): Cache[E, I, B] =
    new Cache[E, I, B] {
      override def value(i: => I): IO[E, B] =
        IO(output)

      override def isCached: Boolean =
        true

      override def isStored: Boolean =
        true

      override def clear(): Unit =
        ()

      override def getIO(): Option[IO.Right[E, B]] =
        Option(IO.Right(output))

      override def getOrElse[F >: E : IO.ExceptionHandler, BB >: B](f: => IO[F, BB]): IO[F, BB] =
        IO[F, BB](output)

    }

  def emptyValuesBlock[E: IO.ExceptionHandler]: Cache[E, ValuesBlock.Offset, UnblockedReader[ValuesBlock.Offset, ValuesBlock]] =
    Cache.concurrentIO[E, ValuesBlock.Offset, UnblockedReader[ValuesBlock.Offset, ValuesBlock]](synchronised = false, initial = None, stored = true) {
      case (_, _) =>
        IO(ValuesBlock.emptyUnblocked)
    }

  def concurrentIO[E: IO.ExceptionHandler, I, O](synchronised: Boolean,
                                                 stored: Boolean,
                                                 initial: Option[O])(fetch: (I, Cache[E, I, O]) => IO[E, O]): Cache[E, I, O] =
    new SynchronisedIO[E, I, O](
      fetch = fetch,
      lazyIO =
        Lazy.io(
          synchronised = synchronised,
          initial = initial,
          stored = stored
        )
    )

  def reservedIO[E: IO.ExceptionHandler, ER <: E with swaydb.Error.Recoverable, I, O](stored: Boolean,
                                                                                      reserveError: ER,
                                                                                      initial: Option[O])(fetch: (I, Cache[E, I, O]) => IO[E, O]): Cache[E, I, O] =
    new ReservedIO[E, ER, I, O](
      fetch = fetch,
      error = reserveError,
      lazyIO =
        Lazy.io(
          synchronised = false,
          initial = initial,
          stored = stored
        )
    )

  def noIO[I, O](synchronised: Boolean,
                 stored: Boolean,
                 initial: Option[O])(fetch: (I, CacheNoIO[I, O]) => O): CacheNoIO[I, O] =
    new CacheNoIO[I, O](
      fetch = fetch,
      lazyValue =
        Lazy.value(
          synchronised = synchronised,
          stored = stored,
          initial = initial
        )
    )

  def io[E: IO.ExceptionHandler, ER <: E with swaydb.Error.Recoverable, I, O](strategy: IOStrategy,
                                                                              reserveError: => ER,
                                                                              initial: Option[O])(fetch: (I, Cache[E, I, O]) => IO[E, O]): Cache[E, I, O] =
    strategy match {
      case IOStrategy.ConcurrentIO(cacheOnAccess) =>
        Cache.concurrentIO[E, I, O](
          synchronised = false,
          initial = initial,
          stored = cacheOnAccess
        )(fetch)

      case IOStrategy.SynchronisedIO(cacheOnAccess) =>
        Cache.concurrentIO[E, I, O](
          synchronised = true,
          initial = initial,
          stored = cacheOnAccess
        )(fetch)

      case IOStrategy.AsyncIO(cacheOnAccess) =>
        Cache.reservedIO[E, ER, I, O](
          stored = cacheOnAccess,
          initial = initial,
          reserveError = reserveError
        )(fetch)
    }

  def deferredIO[E: IO.ExceptionHandler, ER <: E with swaydb.Error.Recoverable, I, O](strategy: I => IOStrategy,
                                                                                      reserveError: => ER)(fetch: (I, Cache[E, I, O]) => IO[E, O]): Cache[E, I, O] =
    new BlockIOCache[E, I, O](
      Cache.noIO[I, Cache[E, I, O]](synchronised = true, stored = true, initial = None) {
        (i, _) =>
          val ioStrategy = Functions.safe((_: I) => IOStrategy.SynchronisedIO(false), strategy)(i)
          Cache.io[E, ER, I, O](
            strategy = ioStrategy,
            reserveError = reserveError,
            initial = None
          )(fetch)
      }
    )
}

/**
 * Caches a value on read. Used for IO operations where the output does not change.
 * For example: A file's size.
 */
private[core] sealed abstract class Cache[+E: IO.ExceptionHandler, -I, +O] extends LazyLogging { self =>
  def value(i: => I): IO[E, O]
  def isCached: Boolean
  def isStored: Boolean
  def clear(): Unit
  def getIO(): Option[IO.Right[E, O]]
  def get(): Option[O] = getIO().map(_.get)

  def getOrElse[F >: E : IO.ExceptionHandler, B >: O](f: => IO[F, B]): IO[F, B]

  def getSomeOrElse[F >: E : IO.ExceptionHandler, B >: O](f: => IO[F, Option[B]]): IO[F, Option[B]] =
    getIO().map(_.toOptionValue) getOrElse f

  /**
   * An adapter function that applies the map function to the input on each invocation.
   * The result does not get stored in this cache.
   *
   * [[mapConcurrentStored]] Or [[flatMap]] functions are used for where storage is required.
   */
  def map[F >: E : IO.ExceptionHandler, B](f: O => IO[F, B]): Cache[F, I, B] =
    new Cache[F, I, B] {
      override def value(i: => I): IO[F, B] =
        self.value(i).flatMap(f)

      override def isCached: Boolean =
        self.isCached

      override def isStored: Boolean =
        self.isStored

      override def getOrElse[FF >: F : IO.ExceptionHandler, BB >: B](f: => IO[FF, BB]): IO[FF, BB] =
        getIO() getOrElse f

      override def getIO(): Option[IO.Right[F, B]] =
        self.getIO() flatMap {
          success =>
            success.flatMap(f) match {
              case success: IO.Right[F, B] =>
                Some(success)

              case ex: IO.Left[F, B] =>
                logger.error("Failed to apply map function on Cache.", ex.exception)
                None
            }
        }
      override def clear(): Unit =
        self.clear()

    }

  //  def mapConcurrentStored[F >: E : IO.ExceptionHandler, O2](f: (O, Cache[F, I, O2]) => IO[F, O2]): Cache[F, I, O2] =
  //    flatMap(Cache.concurrentIO[F, I, O2](synchronised = false, stored = true, initial = None)(f))

  def flatMap[F >: E : IO.ExceptionHandler, B](next: Cache[F, O, B]): Cache[F, I, B] =
    new Cache[F, I, B] {
      override def value(i: => I): IO[F, B] =
        getOrElse(self.value(i).flatMap(next.value(_)))

      override def isCached: Boolean =
        self.isCached || next.isCached

      override def isStored: Boolean =
        self.isStored || next.isStored

      override def getOrElse[FF >: F : IO.ExceptionHandler, BB >: B](f: => IO[FF, BB]): IO[FF, BB] =
        next getOrElse f

      /**
       * If [[next]] is not already cached see if [[self]] is cached
       * and send it's value to [[next]]'s cache to populate.
       */
      override def getIO(): Option[IO.Right[F, B]] =
        next.getIO() orElse {
          self.getIO() flatMap {
            value =>
              next.value(value.get) match {
                case success @ IO.Right(_) =>
                  Some(success)

                case failure @ IO.Left(_) =>
                  logger.error("Failed to apply flatMap function on Cache.", failure.exception)
                  None
              }
          }
        }

      override def clear(): Unit = {
        next.clear()
        self.clear()
      }
    }
}

private class BlockIOCache[E: IO.ExceptionHandler, -I, +B](cache: CacheNoIO[I, Cache[E, I, B]]) extends Cache[E, I, B] {

  override def isStored: Boolean =
    cache.isStored

  override def value(i: => I): IO[E, B] = {
    //ensure that i is not executed multiple times.
    var executed: I = null.asInstanceOf[I]

    def fetch =
      if (executed == null) {
        executed = i
        executed
      } else {
        executed
      }

    cache.value(fetch).value(fetch)
  }

  override def isCached: Boolean =
    cache.get() exists (_.isCached)

  override def getOrElse[F >: E : IO.ExceptionHandler, BB >: B](f: => IO[F, BB]): IO[F, BB] =
    getIO() getOrElse f

  override def clear(): Unit = {
    cache.get() foreach (_.clear())
    cache.clear()
  }

  override def getIO(): Option[IO.Right[E, B]] =
    cache.get().flatMap(_.getIO())

}

private class SynchronisedIO[E: IO.ExceptionHandler, -I, +B](fetch: (I, Cache[E, I, B]) => IO[E, B],
                                                             lazyIO: LazyIO[E, B]) extends Cache[E, I, B] {

  def isStored: Boolean =
    lazyIO.stored

  override def value(i: => I): IO[E, B] =
    lazyIO getOrSet fetch(i, this)

  override def getOrElse[F >: E : IO.ExceptionHandler, BB >: B](f: => IO[F, BB]): IO[F, BB] =
    lazyIO getOrElse f

  override def isCached: Boolean =
    lazyIO.isDefined

  override def clear(): Unit =
    lazyIO.clear()

  override def getIO(): Option[IO.Right[E, B]] =
    lazyIO.get()
}

/**
 * Caches a value on read. Used for IO operations where the output does not change.
 * For example: A file's size.
 */
private class ReservedIO[E: IO.ExceptionHandler, ER <: E with swaydb.Error.Recoverable, -I, +O](fetch: (I, Cache[E, I, O]) => IO[E, O],
                                                                                                lazyIO: LazyIO[E, O],
                                                                                                error: ER) extends Cache[E, I, O] {

  def isStored: Boolean =
    lazyIO.stored

  override def value(i: => I): IO[E, O] =
    lazyIO getOrElse {
      if (Reserve.setBusyOrGet((), error.reserve).isEmpty)
        try
          lazyIO getOrElse (lazyIO set fetch(i, this)) //check if it's set again in the block.
        finally
          Reserve.setFree(error.reserve)
      else
        IO.Left[E, O](error)
    }

  override def isCached: Boolean =
    lazyIO.isDefined

  override def getOrElse[F >: E : IO.ExceptionHandler, BB >: O](f: => IO[F, BB]): IO[F, BB] =
    lazyIO getOrElse f

  override def clear() =
    lazyIO.clear()

  override def getIO(): Option[IO.Right[E, O]] =
    lazyIO.get()
}

/**
 * Caches a value on read. Used for IO operations where the output does not change.
 * For example: A file's size.
 */
private[swaydb] class CacheNoIO[-I, +O](fetch: (I, CacheNoIO[I, O]) => O, lazyValue: LazyValue[O]) {

  def isStored: Boolean =
    lazyValue.stored

  def value(input: => I): O =
    lazyValue getOrSet fetch(input, this)

  def applyOrFetchApply[E: IO.ExceptionHandler, T](apply: O => IO[E, T], fetch: => IO[E, I]): IO[E, T] =
    lazyValue.get() match {
      case Some(input) =>
        apply(input)

      case None =>
        fetch flatMap {
          input =>
            apply(value(input))
        }
    }

  def isCached: Boolean =
    lazyValue.isDefined

  def getOrElse[OO >: O](f: => OO): OO =
    lazyValue getOrElse f

  def get() =
    lazyValue.get()

  def clear() =
    lazyValue.clear()
}
