package swaydb.core.util

import scala.reflect.ClassTag

object HashSlot {
  def apply[K, V: ClassTag](maxSize: Int) =
    new HashSlot[K, V](new Array[V](maxSize))
}

class HashSlot[K, V](array: Array[V]) {

  val arrayLength = array.length

  def put(key: K, value: V) =
    array(Math.abs(key.##) % arrayLength) = value

  def get(key: K): Option[V] =
    Option(array(Math.abs(key.##) % arrayLength))

  def foreach[U](value: V => U): Unit =
    array.foreach(value)

  def mkString(sep: String) =
    array.mkString(sep)

  override def toString: String =
    mkString(", ")
}