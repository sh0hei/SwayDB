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

package swaydb

import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.Try
import swaydb.data.IO
import scala.concurrent.duration._

/**
  * New Wrappers can be implemented by extending this trait.
  */
trait Wrap[W[_]] {
  def apply[A](a: => A): W[A]
  def foreach[A, B](a: A)(f: A => B): Unit
  def map[A, B](a: A)(f: A => B): W[B]
  def flatMap[A, B](fa: W[A])(f: A => W[B]): W[B]
  def unsafeGet[A](b: W[A]): A
  def success[A](value: A): W[A]
  def none[A]: W[Option[A]]
  private[swaydb] def terminate[A]: W[A] = none.asInstanceOf[W[A]]
}

object Wrap {
  implicit val tryWrap = new Wrap[Try] {
    override def apply[A](a: => A): Try[A] = Try(a)
    override def map[A, B](a: A)(f: A => B): Try[B] = Try(f(a))
    override def foreach[A, B](a: A)(f: A => B): Unit = f(a)
    override def flatMap[A, B](fa: Try[A])(f: A => Try[B]): Try[B] = fa.flatMap(f)
    override def unsafeGet[A](b: Try[A]): A = b.get
    override def success[A](value: A): Try[A] = scala.util.Success(value)
    override def none[A]: Try[Option[A]] = scala.util.Success(None)
  }

  implicit val ioWrap = new Wrap[IO] {
    override def apply[A](a: => A): IO[A] = IO(a)
    override def map[A, B](a: A)(f: A => B): IO[B] = IO(f(a))
    override def foreach[A, B](a: A)(f: A => B): Unit = f(a)
    override def flatMap[A, B](fa: IO[A])(f: A => IO[B]): IO[B] = fa.flatMap(f)
    override def success[A](value: A): IO[A] = IO.Success(value)
    override def unsafeGet[A](b: IO[A]): A = b.get
    override def none[A]: IO[Option[A]] = IO.none
  }

  implicit def futureWrap(implicit ec: ExecutionContext): Wrap[Future] =
    futureWrap()

  implicit def futureWrap(timeout: FiniteDuration = 10.seconds)(implicit ec: ExecutionContext) = new Wrap[Future] {
    override def apply[A](a: => A): Future[A] = Future(a)
    override def map[A, B](a: A)(f: A => B): Future[B] = Future(f(a))
    override def flatMap[A, B](fa: Future[A])(f: A => Future[B]): Future[B] = fa.flatMap(f)
    override def unsafeGet[A](b: Future[A]): A = Await.result(b, timeout)
    override def success[A](value: A): Future[A] = Future.successful(value)
    override def none[A]: Future[Option[A]] = Future.successful(None)
    override def foreach[A, B](a: A)(f: A => B): Unit = f(a)
  }

  implicit class WrapImplicits[A, W[_] : Wrap](a: W[A])(implicit wrap: Wrap[W]) {
    @inline def map[B](f: A => B): W[B] =
      wrap.flatMap(a) {
        a =>
          wrap.map[A, B](a)(f)
      }

    @inline def foreach[B](f: A => B): Unit =
      wrap.flatMap(a) {
        aa: A =>
          wrap.foreach[A, B](aa)(f)
          a
      }

    @inline def flatMap[B](f: A => W[B]): W[B] =
      wrap.flatMap(a)(f)

    @inline def get: A =
      wrap.unsafeGet(a)
  }
}