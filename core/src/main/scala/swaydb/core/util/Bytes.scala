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

import swaydb.Done
import swaydb.core.data.KeyValue
import swaydb.core.io.reader.Reader
import swaydb.data.slice.Slice
import swaydb.data.util.Bytez

private[swaydb] object Bytes extends Bytez {

  val zero = 0.toByte
  val one = 1.toByte

  def commonPrefixBytesCount(previous: Slice[Byte],
                             next: Slice[Byte]): Int = {
    val min = Math.min(previous.size, next.size)
    var i = 0
    while (i < min && previous(i) == next(i))
      i += 1
    i
  }

  def commonPrefixBytes(previous: Slice[Byte],
                        next: Slice[Byte]): Slice[Byte] = {
    val commonBytes = commonPrefixBytesCount(previous, next)
    if (previous.size <= next.size)
      next take commonBytes
    else
      previous take commonBytes
  }

  def compress(key: Slice[Byte],
               previous: KeyValue,
               minimumCommonBytes: Int): Option[(Int, Slice[Byte])] =
    compress(
      previous = previous.key,
      next = key,
      minimumCommonBytes = minimumCommonBytes
    )

  def compress(previous: Slice[Byte],
               next: Slice[Byte],
               minimumCommonBytes: Int): Option[(Int, Slice[Byte])] = {
    val commonBytes = Bytes.commonPrefixBytesCount(previous, next)
    if (commonBytes < minimumCommonBytes)
      None
    else
      Some(commonBytes, next.drop(commonBytes))
  }

  def compressFull(previous: Option[Slice[Byte]],
                   next: Slice[Byte]): Option[Done] =
    previous flatMap {
      previous =>
        compressFull(
          previous = previous,
          next = next
        )
    }

  def compressFull(previous: Slice[Byte],
                   next: Slice[Byte]): Option[Done] =
    if (previous.size < next.size)
      None
    else
      compress(previous, next, next.size) map {
        _ =>
          Done.instance
      }

  def compressExact(previous: Slice[Byte],
                    next: Slice[Byte]): Option[Done] =
    if (previous.size != next.size)
      None
    else
      compressFull(previous, next)

  def decompress(previous: Slice[Byte],
                 next: Slice[Byte],
                 commonBytes: Int): Slice[Byte] = {
    val missingCommonBytes = previous.slice(0, commonBytes - 1)
    val fullKey = new Array[Byte](commonBytes + next.size)
    var i = 0
    while (i < commonBytes) {
      fullKey(i) = missingCommonBytes(i)
      i += 1
    }
    var x = 0
    while (x < next.size) {
      fullKey(i) = next(x)
      x += 1
      i += 1
    }
    Slice(fullKey)
  }

  def sizeOfUnsignedInt(int: Int): Int =
    if (int < 0)
      5
    else if (int < 0x80)
      1
    else if (int < 0x4000)
      2
    else if (int < 0x200000)
      3
    else if (int < 0x10000000)
      4
    else
      5

  def sizeOfUnsignedLong(long: Long): Int =
    if (long < 0L)
      10
    else if (long < 0x80L)
      1
    else if (long < 0x4000L)
      2
    else if (long < 0x200000L)
      3
    else if (long < 0x10000000L)
      4
    else if (long < 0x800000000L)
      5
    else if (long < 0x40000000000L)
      6
    else if (long < 0x2000000000000L)
      7
    else if (long < 0x100000000000000L)
      8
    else
      9

  def compressJoin(left: Slice[Byte],
                   right: Slice[Byte]): Slice[Byte] =
    compressJoin(
      left = left,
      right = right,
      tail = Slice.emptyBytes
    )

  def compressJoin(left: Slice[Byte],
                   right: Slice[Byte],
                   tail: Byte): Slice[Byte] =
    compressJoin(
      left = left,
      right = right,
      tail = Slice(tail)
    )

  /**
   * Merges the input bytes into a single byte array extracting common bytes.
   *
   * If there are no common bytes the compress will result in 2 more additional bytes.
   *
   * tail bytes are also appended to the the result. When decompressing tail bytes should be stripped.
   */
  def compressJoin(left: Slice[Byte],
                   right: Slice[Byte],
                   tail: Slice[Byte]): Slice[Byte] = {
    val commonBytes = commonPrefixBytesCount(left, right)
    val rightWithoutCommonBytes =
      if (commonBytes != 0)
        right.drop(commonBytes)
      else
        right

    //if right was fully compressed just store right bytes with commonBytes integer. During read commonBytes int will be checked
    //to see if its the same size as left and the same left bytes will be returned for right as well.
    if (rightWithoutCommonBytes.isEmpty) {
      val compressedSlice = Slice.create[Byte](left.size + sizeOfUnsignedInt(commonBytes) + sizeOfUnsignedInt(left.size) + tail.size)
      compressedSlice addAll left
      compressedSlice addUnsignedInt commonBytes
      compressedSlice addAll Bytez.writeUnsignedIntReversed(left.size) //store key1's byte size to the end to allow further merges with other keys.
      compressedSlice addAll tail
    } else {
      val size =
        left.size +
          sizeOfUnsignedInt(commonBytes) +
          sizeOfUnsignedInt(rightWithoutCommonBytes.size) +
          rightWithoutCommonBytes.size +
          sizeOfUnsignedInt(left.size) +
          tail.size

      val compressedSlice = Slice.create[Byte](size)
      compressedSlice addAll left
      compressedSlice addUnsignedInt commonBytes
      compressedSlice addUnsignedInt rightWithoutCommonBytes.size
      compressedSlice addAll rightWithoutCommonBytes
      compressedSlice addAll Bytez.writeUnsignedIntReversed(left.size) //store key1's byte size to the end to allow further merges with other keys.
      compressedSlice addAll tail
    }
  }

  def decompressJoin(bytes: Slice[Byte]): (Slice[Byte], Slice[Byte]) = {

    val reader = Reader(bytes)
    val (leftBytesSize, lastBytesRead) = Bytez.readLastUnsignedInt(bytes)
    val left = reader.read(leftBytesSize)
    val commonBytes = reader.readUnsignedInt()
    val hasMore = reader.hasAtLeast(lastBytesRead + 1) //if there are more bytes to read.

    val right =
      if (!hasMore && commonBytes == leftBytesSize) { //if right was fully compressed then right == left, return left.
        left
      } else {
        val rightSize = reader.readUnsignedInt()
        val right = reader.read(rightSize)
        decompress(left, right, commonBytes)
      }

    (left, right)
  }

  def normalise(bytes: Slice[Byte], toSize: Int): Slice[Byte] = {
    assert(bytes.size < toSize, s"bytes.size(${bytes.size}) >= toSize($toSize)")
    val finalSlice = Slice.create[Byte](toSize)
    var zeroesToAdd = toSize - bytes.size - 1
    while (zeroesToAdd > 0) {
      finalSlice add Bytes.zero
      zeroesToAdd -= 1
    }
    finalSlice add Bytes.one
    finalSlice addAll bytes
  }

  def normalise(appendHeader: Slice[Byte],
                bytes: Slice[Byte],
                toSize: Int): Slice[Byte] = {
    assert((appendHeader.size + bytes.size) < toSize, s"appendHeader.size(${appendHeader.size}) + bytes.size(${bytes.size}) >= toSize($toSize)")
    val finalSlice = Slice.create[Byte](appendHeader.size + toSize)
    finalSlice addAll appendHeader
    var zeroesToAdd = toSize - appendHeader.size - bytes.size - 1
    while (zeroesToAdd > 0) {
      finalSlice add Bytes.zero
      zeroesToAdd -= 1
    }
    finalSlice add Bytes.one
    finalSlice addAll bytes
  }

  /**
   * Does not validate if the input bytes are normalised bytes.
   * It simply drops upto the first 1.byte.
   *
   * Similar function [[Slice.dropTo]] which is not directly to avoid
   * creation of [[Some]] object.
   */
  def deNormalise(bytes: Slice[Byte]): Slice[Byte] =
    bytes drop (bytes.indexOf(Bytes.one).get + 1)
}
