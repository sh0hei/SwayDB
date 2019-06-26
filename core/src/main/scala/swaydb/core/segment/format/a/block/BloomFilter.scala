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

package swaydb.core.segment.format.a.block

import com.typesafe.scalalogging.LazyLogging
import swaydb.compression.CompressionInternal
import swaydb.core.data.KeyValue
import swaydb.core.segment.format.a.OffsetBase
import swaydb.core.util.{Bytes, MurmurHash3Generic, Options}
import swaydb.data.IO
import swaydb.data.IO._
import swaydb.data.slice.{Reader, Slice}
import swaydb.data.util.ByteSizeOf

object BloomFilter extends LazyLogging {

  case class Offset(start: Int, size: Int) extends OffsetBase

  case class State(startOffset: Int,
                   numberOfBits: Int,
                   maxProbe: Int,
                   headerSize: Int,
                   var _bytes: Slice[Byte],
                   compressions: Seq[CompressionInternal]) {

    def bytes = _bytes

    def bytes_=(bytes: Slice[Byte]) =
      this._bytes = bytes

    def written =
      bytes.written

    override def hashCode(): Int =
      bytes.hashCode()
  }

  def optimalSize(numberOfKeys: Int,
                  falsePositiveRate: Double): Int = {
    if (falsePositiveRate <= 0.0 || numberOfKeys <= 0) {
      0
    } else {
      val numberOfBits = optimalNumberOfBits(numberOfKeys, falsePositiveRate)
      val maxProbe = optimalNumberOfProbes(numberOfKeys, numberOfBits)

      val numberOfBitsSize = Bytes.sizeOf(numberOfBits)
      val maxProbeSize = Bytes.sizeOf(maxProbe)

      BlockCompression.headerSize +
        numberOfBitsSize +
        maxProbeSize +
        numberOfBits
    }
  }

  def apply(numberOfKeys: Int,
            falsePositiveRate: Double,
            compressions: Seq[CompressionInternal]): BloomFilter.State = {
    val numberOfBits = optimalNumberOfBits(numberOfKeys, falsePositiveRate)
    val maxProbe = optimalNumberOfProbes(numberOfKeys, numberOfBits)

    val numberOfBitsSize = Bytes.sizeOf(numberOfBits)
    val maxProbeSize = Bytes.sizeOf(maxProbe)

    val headerBytesSize =
      (if (compressions.isEmpty) BlockCompression.headerSizeNoCompression else BlockCompression.headerSize) +
        numberOfBitsSize +
        maxProbeSize

    val headerSize =
      Bytes.sizeOf(headerBytesSize) +
        headerBytesSize

    val bytes = Slice.create[Byte](headerSize + numberOfBits)

    BloomFilter.State(
      startOffset = headerSize,
      numberOfBits = numberOfBits,
      headerSize = headerSize,
      maxProbe = maxProbe,
      _bytes = bytes,
      compressions = compressions
    )
  }

  def optimalNumberOfBits(numberOfKeys: Int, falsePositiveRate: Double): Int =
    if (numberOfKeys <= 0 || falsePositiveRate <= 0.0)
      0
    else
      math.ceil(-1 * numberOfKeys * math.log(falsePositiveRate) / math.log(2) / math.log(2)).toInt

  def optimalNumberOfProbes(numberOfKeys: Int, numberOfBits: Long): Int =
    if (numberOfKeys <= 0 || numberOfBits <= 0)
      0
    else
      math.ceil(numberOfBits / numberOfKeys * math.log(2)).toInt

  def close(state: State) = {
    BlockCompression.compressAndUpdateHeader(
      headerSize = state.headerSize,
      bytes = state.bytes,
      compressions = state.compressions
    ) flatMap {
      compressedOrUncompressedBytes =>
        IO {
          state.bytes = compressedOrUncompressedBytes
          state.bytes addIntUnsigned state.numberOfBits
          state.bytes addIntUnsigned state.maxProbe
          if (state.bytes.currentWritePosition > state.headerSize)
            throw new Exception(s"Calculated header size was incorrect. Expected: ${state.headerSize}. Used: ${state.bytes.currentWritePosition - 1}")
        }
    }
  }

  def read(offset: Offset,
           reader: Reader): IO[BloomFilter] =
    BlockCompression.readHeader(offset = offset, reader = reader) flatMap {
      result =>
        for {
          numberOfBits <- result.headerReader.readIntUnsigned()
          maxProbe <- result.headerReader.readIntUnsigned()
        } yield
          BloomFilter(
            offset =
              if (result.blockCompression.isDefined)
                offset
              else
                Offset(
                  start = offset.start + result.headerSize,
                  size = offset.size - result.headerSize
                ),
            maxProbe = maxProbe,
            numberOfBits = numberOfBits,
            blockCompression = result.blockCompression
          )
    }

  def shouldNotCreateBloomFilter(keyValues: Iterable[KeyValue.WriteOnly]): Boolean =
    keyValues.isEmpty ||
      keyValues.last.stats.segmentHasRemoveRange ||
      keyValues.last.stats.segmentBloomFilterSize <= 1 ||
      keyValues.last.falsePositiveRate <= 0.0

  def shouldCreateBloomFilter(keyValues: Iterable[KeyValue.WriteOnly]): Boolean =
    !shouldNotCreateBloomFilter(keyValues)

  def init(keyValues: Iterable[KeyValue.WriteOnly],
           compressions: Seq[CompressionInternal]): Option[BloomFilter.State] =
    if (shouldCreateBloomFilter(keyValues))
      init(
        numberOfKeys = keyValues.last.stats.segmentUniqueKeysCount,
        falsePositiveRate = keyValues.last.falsePositiveRate,
        compressions = compressions
      )
    else
      None

  /**
    * Initialise bloomFilter if key-values do no contain remove range.
    */
  def init(numberOfKeys: Int,
           falsePositiveRate: Double,
           compressions: Seq[CompressionInternal]): Option[BloomFilter.State] =
    if (numberOfKeys <= 0 || falsePositiveRate <= 0.0)
      None
    else
      Some(
        BloomFilter(
          numberOfKeys = numberOfKeys,
          falsePositiveRate = falsePositiveRate,
          compressions = compressions
        )
      )

  def add(key: Slice[Byte],
          state: BloomFilter.State): Unit = {
    val hash = MurmurHash3Generic.murmurhash3_x64_64(key, 0, key.size, 0)
    val hash1 = hash >>> 32
    val hash2 = (hash << 32) >> 32

    var probe = 0
    while (probe < state.maxProbe) {
      val computedHash = hash1 + probe * hash2
      val hashIndex = (computedHash & Long.MaxValue) % state.numberOfBits
      val offset = (state.startOffset + (hashIndex >>> 6) * 8L).toInt
      val long = state.bytes.take(offset, ByteSizeOf.long).readLong()
      if ((long & (1L << hashIndex)) == 0) {
        state.bytes moveWritePosition offset
        state.bytes addLong (long | (1L << hashIndex))
      }
      probe += 1
    }
  }

  def mightContain(key: Slice[Byte],
                   reader: Reader,
                   bloom: BloomFilter): IO[Boolean] = {
    val hash = MurmurHash3Generic.murmurhash3_x64_64(key, 0, key.size, 0)
    val hash1 = hash >>> 32
    val hash2 = (hash << 32) >> 32

    bloom
      .blockCompression
      .map {
        blockDecompressor =>
          BlockCompression.getDecompressedReader(
            blockDecompressor = blockDecompressor,
            compressedReader = reader,
            offset = bloom.offset
          ) map ((0, _)) //decompressed bytes, offsets not required, set to 0.
      }
      .getOrElse {
        IO.Success((bloom.offset.start, reader)) //no compression used. Set the offset.
      }
      .flatMap {
        case (startOffset, reader) =>
          (0 until bloom.maxProbe)
            .untilSomeResult {
              probe =>
                val computedHash = hash1 + probe * hash2
                val hashIndex = (computedHash & Long.MaxValue) % bloom.numberOfBits

                reader
                  .moveTo(startOffset + ((hashIndex >>> 6) * 8L).toInt)
                  .readLong()
                  .map {
                    index =>
                      if ((index & (1L << hashIndex)) == 0)
                        Options.someFalse
                      else
                        None
                  }
            }
            .map(_.getOrElse(true))
      }
  }
}

case class BloomFilter(offset: BloomFilter.Offset,
                       maxProbe: Int,
                       numberOfBits: Int,
                       blockCompression: Option[BlockCompression.State])