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

package swaydb.core.segment.format.a.block.hashindex

import com.typesafe.scalalogging.LazyLogging
import swaydb.compression.CompressionInternal
import swaydb.core.data.{Persistent, Transient}
import swaydb.core.segment.format.a.block.KeyMatcher.Result
import swaydb.core.segment.format.a.block._
import swaydb.core.segment.format.a.block.reader.UnblockedReader
import swaydb.core.util.{Bytes, CRC32}
import swaydb.data.config.{IOAction, IOStrategy, RandomKeyIndex, UncompressedBlockInfo}
import swaydb.data.order.KeyOrder
import swaydb.data.slice.Slice
import swaydb.data.util.{ByteSizeOf, Functions}

import scala.annotation.tailrec
import scala.beans.BeanProperty
import scala.collection.mutable.ListBuffer

/**
 * HashIndex.
 */
private[core] object HashIndexBlock extends LazyLogging {

  val blockName = this.getClass.getSimpleName.dropRight(1)

  object Config {
    val disabled =
      Config(
        maxProbe = -1,
        minimumNumberOfKeys = Int.MaxValue,
        allocateSpace = _ => Int.MinValue,
        minimumNumberOfHits = Int.MaxValue,
        copyIndex = false,
        ioStrategy = dataType => IOStrategy.SynchronisedIO(cacheOnAccess = dataType.isCompressed),
        compressions = _ => Seq.empty
      )

    def apply(config: swaydb.data.config.RandomKeyIndex): Config =
      config match {
        case swaydb.data.config.RandomKeyIndex.Disable =>
          Config(
            maxProbe = -1,
            minimumNumberOfKeys = Int.MaxValue,
            allocateSpace = _ => Int.MinValue,
            minimumNumberOfHits = Int.MaxValue,
            copyIndex = false,
            ioStrategy = dataType => IOStrategy.SynchronisedIO(cacheOnAccess = dataType.isCompressed),
            compressions = _ => Seq.empty
          )

        case enable: swaydb.data.config.RandomKeyIndex.Enable =>
          Config(
            maxProbe = enable.maxProbe,
            minimumNumberOfKeys = enable.minimumNumberOfKeys,
            minimumNumberOfHits = enable.minimumNumberOfHits,
            copyIndex = enable.copyKeys,
            allocateSpace = Functions.safe(_.requiredSpace, enable.allocateSpace),
            ioStrategy = Functions.safe(IOStrategy.synchronisedStoredIfCompressed, enable.ioStrategy),
            compressions =
              Functions.safe(
                default = _ => Seq.empty[CompressionInternal],
                function = enable.compression(_) map CompressionInternal.apply
              )
          )
      }
  }

  case class Config(maxProbe: Int,
                    minimumNumberOfKeys: Int,
                    minimumNumberOfHits: Int,
                    copyIndex: Boolean,
                    allocateSpace: RandomKeyIndex.RequiredSpace => Int,
                    ioStrategy: IOAction => IOStrategy,
                    compressions: UncompressedBlockInfo => Seq[CompressionInternal])

  case class Offset(start: Int, size: Int) extends BlockOffset

  final case class State(var hit: Int,
                         var miss: Int,
                         copyIndex: Boolean,
                         minimumNumberOfKeys: Int,
                         minimumNumberOfHits: Int,
                         writeAbleLargestValueSize: Int,
                         @BeanProperty var minimumCRC: Long,
                         headerSize: Int,
                         maxProbe: Int,
                         var _bytes: Slice[Byte],
                         compressions: UncompressedBlockInfo => Seq[CompressionInternal]) {

    def bytes = _bytes

    def bytes_=(bytes: Slice[Byte]) =
      this._bytes = bytes

    def hasMinimumHits =
      hit >= minimumNumberOfHits
  }

  def init(keyValues: Iterable[Transient]): Option[HashIndexBlock.State] =
    if (keyValues.size < keyValues.last.hashIndexConfig.minimumNumberOfKeys || keyValues.last.stats.segmentHashIndexSize <= 0) {
      None
    } else {
      val last = keyValues.last

      val writeAbleLargestValueSize =
        if (last.hashIndexConfig.copyIndex)
          last.stats.segmentMaxSortedIndexEntrySize +
            ByteSizeOf.long + //varLong == CRC bytes
            ByteSizeOf.int //accessIndexOffset
        else
          Bytes.sizeOfUnsignedInt(last.stats.thisKeyValuesAccessIndexOffset + 1)

      val hasCompression = last.hashIndexConfig.compressions(UncompressedBlockInfo(last.stats.segmentHashIndexSize)).nonEmpty

      val headSize =
        headerSize(
          keyCounts = last.stats.uncompressedKeyCounts,
          writeAbleLargestValueSize = writeAbleLargestValueSize,
          hasCompression = hasCompression
        )

      val optimalBytes =
        optimalBytesRequired(
          keyCounts = last.stats.uncompressedKeyCounts,
          minimumNumberOfKeys = last.hashIndexConfig.minimumNumberOfKeys,
          writeAbleLargestValueSize = writeAbleLargestValueSize,
          allocateSpace = last.hashIndexConfig.allocateSpace,
          copyIndex = last.hashIndexConfig.copyIndex,
          hasCompression = hasCompression
        )

      //if the user allocated
      if (optimalBytes < headSize + ByteSizeOf.varInt)
        None
      else
        Some(
          HashIndexBlock.State(
            hit = 0,
            miss = 0,
            copyIndex = last.hashIndexConfig.copyIndex,
            minimumNumberOfKeys = last.hashIndexConfig.minimumNumberOfKeys,
            minimumNumberOfHits = last.hashIndexConfig.minimumNumberOfHits,
            writeAbleLargestValueSize = writeAbleLargestValueSize,
            minimumCRC = CRC32.disabledCRC,
            headerSize = headSize,
            maxProbe = last.hashIndexConfig.maxProbe,
            _bytes = Slice.create[Byte](optimalBytes),
            compressions =
              //cannot have no compression to begin with a then have compression because that upsets the total bytes required.
              if (hasCompression)
                last.hashIndexConfig.compressions
              else
                _ => Seq.empty
          )
        )
    }

  def headerSize(keyCounts: Int,
                 writeAbleLargestValueSize: Int,
                 hasCompression: Boolean): Int = {
    val headerSize =
      Block.headerSize(hasCompression) +
        ByteSizeOf.int + //allocated bytes
        ByteSizeOf.varInt + //max probe
        ByteSizeOf.boolean + //copyIndex
        (Bytes.sizeOfUnsignedInt(keyCounts) * 2) + //hit & miss rate
        ByteSizeOf.varLong + //minimumCRC
        Bytes.sizeOfUnsignedInt(writeAbleLargestValueSize) //largest value size

    Bytes.sizeOfUnsignedInt(headerSize) +
      headerSize
  }

  def optimalBytesRequired(keyCounts: Int,
                           minimumNumberOfKeys: Int,
                           writeAbleLargestValueSize: Int,
                           hasCompression: Boolean,
                           copyIndex: Boolean,
                           allocateSpace: RandomKeyIndex.RequiredSpace => Int): Int =
    if (keyCounts < minimumNumberOfKeys) {
      0
    } else {
      val sizePerKey =
      //+1 to skip left & right 0 start-end markers if it's not copiedIndex
      //+1 to for the last 1.byte entry so that next entry does overwrite previous writes tail 0's
      //the +1 does not need to be accounted in writeAbleLargestValueSize because these markers are just an indication of start and end index entry.
        keyCounts * (writeAbleLargestValueSize + 1)

      val minimumRequired =
        headerSize(
          keyCounts = keyCounts,
          hasCompression = hasCompression,
          writeAbleLargestValueSize = writeAbleLargestValueSize
        ) + sizePerKey

      try
        allocateSpace(
          RandomKeyIndex.RequiredSpace(
            _requiredSpace = minimumRequired,
            _numberOfKeys = keyCounts
          )
        )
      catch {
        case exception: Exception =>
          logger.error(
            """Custom allocate space calculation for HashIndex returned failure.
              |Using the default requiredSpace instead. Please check your implementation to ensure it's not throwing exception.
            """.stripMargin, exception)
          minimumRequired
      }
    }

  def close(state: State): Option[State] =
    if (state.bytes.isEmpty || !state.hasMinimumHits)
      None
    else {
      val compressedOrUncompressedBytes =
        Block.block(
          headerSize = state.headerSize,
          bytes = state.bytes,
          compressions = state.compressions(UncompressedBlockInfo(state.bytes.size)),
          blockName = blockName
        )

      val allocatedBytes = state.bytes.allocatedSize
      state.bytes = compressedOrUncompressedBytes
      state.bytes addInt allocatedBytes //allocated bytes
      state.bytes addUnsignedInt state.maxProbe
      state.bytes addBoolean state.copyIndex
      state.bytes addUnsignedInt state.hit
      state.bytes addUnsignedInt state.miss
      state.bytes addUnsignedLong {
        //CRC can be -1 when HashIndex is not fully copied.
        if (state.minimumCRC == CRC32.disabledCRC)
          0
        else
          state.minimumCRC
      }
      state.bytes addUnsignedInt state.writeAbleLargestValueSize
      if (state.bytes.currentWritePosition > state.headerSize)
        throw new Exception(s"Calculated header size was incorrect. Expected: ${state.headerSize}. Used: ${state.bytes.currentWritePosition}")
      Some(state)
    }

  def read(header: Block.Header[HashIndexBlock.Offset]): HashIndexBlock = {
    val allocatedBytes = header.headerReader.readInt()
    val maxProbe = header.headerReader.readUnsignedInt()
    val copyIndex = header.headerReader.readBoolean()
    val hit = header.headerReader.readUnsignedInt()
    val miss = header.headerReader.readUnsignedInt()
    val minimumCRC = header.headerReader.readUnsignedLong()
    val largestValueSize = header.headerReader.readUnsignedInt()

    HashIndexBlock(
      offset = header.offset,
      compressionInfo = header.compressionInfo,
      maxProbe = maxProbe,
      copyIndex = copyIndex,
      minimumCRC = minimumCRC,
      hit = hit,
      miss = miss,
      writeAbleLargestValueSize = largestValueSize,
      headerSize = header.headerSize,
      allocatedBytes = allocatedBytes
    )
  }

  def adjustHash(hash: Int,
                 totalBlockSpace: Int,
                 headerSize: Int,
                 writeAbleLargestValueSize: Int) =
    ((hash & Int.MaxValue) % (totalBlockSpace - writeAbleLargestValueSize - headerSize)) + headerSize

  /**
   * Mutates the slice and adds writes the indexOffset to it's hash index.
   */
  def write(key: Slice[Byte],
            value: Int,
            state: State): Boolean = {

    //add 1 to each offset to avoid 0 offsets.
    //0 bytes are reserved as empty bucket markers.
    val valuePlusOne = value + 1
    val valuePlusOneBytes = Bytes.writeUnsignedIntNonZero(valuePlusOne)

    val hash = key.hashCode()
    val hash1 = hash >>> 32
    val hash2 = (hash << 32) >> 32

    @tailrec
    def doWrite(key: Slice[Byte], probe: Int): Boolean =
      if (probe >= state.maxProbe) {
        ////println(s"Key: ${key.readInt()}: write index: miss probe: $probe")
        state.miss += 1
        false
      } else {
        val hashIndex =
          adjustHash(
            hash = hash1 + probe * hash2,
            totalBlockSpace = state.bytes.allocatedSize,
            headerSize = state.headerSize,
            writeAbleLargestValueSize = state.writeAbleLargestValueSize
          )

        val existing = state.bytes.take(hashIndex, valuePlusOneBytes.size + 2) //+1 to reserve left 0 byte another +1 not overwrite next 0.
        if (existing.forall(_ == 0)) {
          state.bytes moveWritePosition (hashIndex + 1)
          state.bytes addAll valuePlusOneBytes
          state.hit += 1
          ////println(s"Key: ${key.readInt()}: write hashIndex: $hashIndex probe: $probe, value: $value, valueBytes: ${Bytes.writeUnsignedIntNonZero(valuePlusOne)} = success")
          true
        } else if (existing.head == 0 && existing.dropHead() == valuePlusOneBytes) { //check if value already exists.
          ////println(s"Key: ${key.readInt()}: write hashIndex: $hashIndex probe: $probe, value: $value, valueBytes: ${Bytes.writeUnsignedIntNonZero(valuePlusOne)} = existing")
          state.hit += 1
          true
        } else {
          ////println(s"Key: ${key.readInt()}: write hashIndex: $hashIndex probe: $probe, value: $value, valueBytes: ${Bytes.writeUnsignedIntNonZero(valuePlusOne)} = failure")
          doWrite(key = key, probe = probe + 1)
        }
      }

    if (state.bytes.allocatedSize == 0)
      false
    else
      doWrite(key, 0)
  }

  /**
   * Finds a key in the hash index.
   *
   * @param assertValue performs find or forward fetch from the currently being read sorted index's hash block.
   */
  private[block] def search[R](key: Slice[Byte],
                               reader: UnblockedReader[HashIndexBlock.Offset, HashIndexBlock],
                               assertValue: Int => Option[R]): Option[R] = {

    val hash = key.hashCode()
    val hash1 = hash >>> 32
    val hash2 = (hash << 32) >> 32

    val hashIndex = reader.block

    @tailrec
    def doFind(probe: Int): Option[R] =
      if (probe >= hashIndex.maxProbe) {
        None
      } else {
        val index =
          adjustHash(
            hash = hash1 + probe * hash2,
            totalBlockSpace = hashIndex.allocatedBytes,
            headerSize = hashIndex.headerSize,
            writeAbleLargestValueSize = hashIndex.writeAbleLargestValueSize
          ) - hashIndex.headerSize

        val possibleValueBytes =
          reader
            .moveTo(index)
            .read(hashIndex.bytesToReadPerIndex)

        ////println(s"Key: ${key.readInt()}: read hashIndex: ${index + hashIndex.headerSize} probe: $probe. sortedIndex bytes: $possibleValueBytes")
        if (possibleValueBytes.isEmpty || possibleValueBytes.head != Bytes.zero) {
          ////println(s"Key: ${key.readInt()}: read hashIndex: ${index + hashIndex.headerSize} probe: $probe = failure - invalid start offset.")
          doFind(probe + 1)
        } else {
          val possibleValueWithoutHeader = possibleValueBytes.dropHead()
          val (possibleValue, bytesRead) = Bytes.readUnsignedIntNonZeroWithByteSize(possibleValueWithoutHeader)
          ////println(s"Key: ${key.readInt()}: read hashIndex: ${index + hashIndex.headerSize} probe: $probe, sortedIndex: ${possibleValue - 1} = reading now!")
          if (possibleValue == 0 || possibleValueWithoutHeader.take(bytesRead).exists(_ == Bytes.zero)) {
            ////println(s"Key: ${key.readInt()}: read hashIndex: ${index + hashIndex.headerSize} probe: $probe, sortedIndex: ${possibleValue - 1}, possibleValue: $possibleValue, containsZero: ${possibleValueWithoutHeader.take(bytesRead).exists(_ == 0)} = failed")
            doFind(probe + 1)
          } else {
            assertValue(possibleValue - 1) match { //assert value removing the 1 added on write.
              case some @ Some(_) =>
                ////println(s"Key: ${key.readInt()}: read hashIndex: ${index + hashIndex.headerSize} probe: $probe, sortedIndex: ${possibleValue - 1} = success")
                some

              case None =>
                ////println(s"Key: ${key.readInt()}: read hashIndex: ${index + hashIndex.headerSize} probe: $probe: sortedIndex: ${possibleValue - 1} = not found")
                doFind(probe + 1)
            }
          }
        }
      }

    doFind(probe = 0)
  }

  /**
   * Writes full copy of the index entry within HashIndex.
   */
  def writeCopied(key: Slice[Byte],
                  value: Slice[Byte],
                  thisKeyValuesAccessOffset: Int,
                  state: State): Boolean = {

    val hash = key.hashCode()
    val hash1 = hash >>> 32
    val hash2 = (hash << 32) >> 32

    //[CRC|Option[accessOffset]|valuesBytes]
    @tailrec
    def doWrite(key: Slice[Byte], probe: Int): Boolean =
      if (probe >= state.maxProbe) {
        ////println(s"Key: ${key.readInt()}: write index: miss probe: $probe")
        state.miss += 1
        false
      } else {
        val hashIndex =
          adjustHash(
            hash = hash1 + probe * hash2,
            totalBlockSpace = state.bytes.allocatedSize,
            headerSize = state.headerSize,
            writeAbleLargestValueSize = state.writeAbleLargestValueSize
          )

        val existing = state.bytes.take(hashIndex, state.writeAbleLargestValueSize)

        if (existing.forall(_ == 0)) {
          state.bytes moveWritePosition hashIndex
          val crc = CRC32.forBytes(value)
          //write as unsignedLong to avoid writing any zeroes.
          state.bytes addUnsignedLong crc

          state.bytes addUnsignedInt thisKeyValuesAccessOffset

          state.bytes addAll value

          if (value.last == 0) //if the last byte is 0 add one to avoid next write overwriting this entry's last byte.
            state.bytes addByte Bytes.one

          if (state.minimumCRC == CRC32.disabledCRC)
            state setMinimumCRC crc
          else
            state setMinimumCRC (crc min state.minimumCRC)

          state.hit += 1
          ////println(s"Key: ${key.readInt()}: write hashIndex: $hashIndex probe: $probe, crcBytes: ${Slice.writeUnsignedLong(crc)}, value: $value, crc: $crc = success")
          true
        } else {
          ////println(s"Key: ${key.readInt()}: write hashIndex: $hashIndex probe: $probe, value: $value = failure")
          doWrite(key = key, probe = probe + 1)
        }
      }

    if (state.bytes.allocatedSize == 0)
      false
    else
      doWrite(key, 0)
  }

  /**
   * Parses bytes written by [[writeCopied]] without CRC bytes.
   * [CRC|Option[accessOffset]|valuesBytes]
   *
   * @return valueBytes, isReference and accessIndexOffset.
   */
  private def parseCopiedValuesBytes(valueBytesWithoutCRC: Slice[Byte]): (Slice[Byte], Int) = {
    //this will be entrySize/sortedIndexOffset if it's a reference else it will be thisKeyValuesAccessIndexOffset.
    val (entrySizeOrAccessIndexOffsetEntry, entrySizeOrAccessIndexOffsetEntryByteSize) = valueBytesWithoutCRC.readUnsignedIntWithByteSize()

    val (entrySize, entryByteSize) =
      valueBytesWithoutCRC
        .drop(entrySizeOrAccessIndexOffsetEntryByteSize)
        .readUnsignedIntWithByteSize()

    val valueBytes =
      valueBytesWithoutCRC
        .drop(entrySizeOrAccessIndexOffsetEntryByteSize)
        .take(entrySize + entryByteSize)

    (valueBytes, entrySizeOrAccessIndexOffsetEntry)
  }

  /**
   * Finds a key in the hash index.
   *
   * @param assertValue performs find or forward fetch from the currently being read sorted index's hash block.
   */
  private[block] def searchCopied[R](key: Slice[Byte],
                                     reader: UnblockedReader[HashIndexBlock.Offset, HashIndexBlock],
                                     assertValue: (Slice[Byte], Int) => Option[R]): Option[R] = {

    val hash = key.hashCode()
    val hash1 = hash >>> 32
    val hash2 = (hash << 32) >> 32

    val block = reader.block

    @tailrec
    def doFind(probe: Int): Option[R] =
      if (probe >= block.maxProbe) {
        None
      } else {
        val hashIndex =
          adjustHash(
            hash = hash1 + probe * hash2,
            totalBlockSpace = block.allocatedBytes,
            headerSize = block.headerSize,
            writeAbleLargestValueSize = block.writeAbleLargestValueSize
          ) - block.headerSize //remove headerSize since the blockReader points to the hashIndex's start offset.

        //[CRC|Option[isRef]|Option[accessOffset]|valuesBytes]

        val possibleValueBytes =
          reader
            .moveTo(hashIndex)
            .read(block.writeAbleLargestValueSize)

        ////println(s"Key: ${key.readInt()}: read hashIndex: ${hashIndex + block.headerSize} probe: $probe. sortedIndex bytes: $possibleValueBytes")
        if (possibleValueBytes.isEmpty || possibleValueBytes.size == 1) {
          ////println(s"Key: ${key.readInt()}: read hashIndex: ${hashIndex + block.headerSize} probe: $probe = failure - invalid start offset.")
          doFind(probe + 1)
        } else {
          //writeAbleLargestValueSize could also read extra tail bytes so fetch only the bytes that are specific to the indexEntry.
          //Read the crc and then read the indexEntry's entrySize and fetch on the indexEntry bytes and then check CRC.

          val (crc, crcByteSize) =
            try
              possibleValueBytes.readUnsignedLongWithByteSize()
            catch {
              case _: ArrayIndexOutOfBoundsException =>
                (-1L, 0)
            }

          if (crc == -1 || crc < reader.block.minimumCRC || possibleValueBytes.size <= crcByteSize) {
            doFind(probe + 1)
          } else {
            val (valueBytes, accessIndexOffset) = parseCopiedValuesBytes(valueBytesWithoutCRC = possibleValueBytes drop crcByteSize)
            if (crc == CRC32.forBytes(valueBytes)) {
              val value =
                try
                  assertValue(valueBytes, accessIndexOffset)
                catch {
                  case _: ArrayIndexOutOfBoundsException =>
                    None

                  case exception: IllegalArgumentException if exception.getMessage.contains("requirement failed") =>
                    None
                }

              value match { //assert value.
                case some @ Some(_) =>
                  ////println(s"Key: ${key.readInt()}: read hashIndex: ${hashIndex + block.headerSize} probe: $probe, entryBytes: $valueBytes = success")
                  some

                case None =>
                  ////println(s"Key: ${key.readInt()}: read hashIndex: ${hashIndex + block.headerSize} probe: $probe: entryBytes: $valueBytes = not found")
                  doFind(probe + 1)
              }
            } else {
              doFind(probe + 1)
            }
          }
        }
      }

    doFind(probe = 0)
  }

  def search(key: Slice[Byte],
             hashIndexReader: UnblockedReader[HashIndexBlock.Offset, HashIndexBlock],
             sortedIndexReader: UnblockedReader[SortedIndexBlock.Offset, SortedIndexBlock],
             valuesReader: Option[UnblockedReader[ValuesBlock.Offset, ValuesBlock]])(implicit keyOrder: KeyOrder[Slice[Byte]]): HashIndexSearchResult = {
    val matcher = KeyMatcher.Get.MatchOnly(key)

    val collisions = ListBuffer.empty[Persistent.Partial]

    val result =
      if (hashIndexReader.block.copyIndex)
        searchCopied(
          key = key,
          reader = hashIndexReader,
          assertValue =
            (indexEntry: Slice[Byte], accessIndexOffset: Int) =>
              SortedIndexBlock.readAndMatch( //do no perform read for next key-value since this indexReader only contains bytes for the current read indexEntry.
                matcher = matcher,
                fromOffset = 0,
                fullRead = false,
                overwriteNextIndexOffset = {
                  val nextIndexOffset = accessIndexOffset + indexEntry.size
                  //if it's the last key-value, nextIndexOffset is None.
                  if (nextIndexOffset == sortedIndexReader.offset.size)
                    None
                  else
                    Some(nextIndexOffset)
                },
                sortedIndexReader =
                  UnblockedReader(
                    bytes = indexEntry,
                    block =
                      SortedIndexBlock(
                        offset = SortedIndexBlock.Offset(0, indexEntry.size),
                        enableAccessPositionIndex = sortedIndexReader.block.enableAccessPositionIndex,
                        hasPrefixCompression = sortedIndexReader.block.hasPrefixCompression,
                        normaliseForBinarySearch = sortedIndexReader.block.normaliseForBinarySearch,
                        disableKeyPrefixCompression = sortedIndexReader.block.disableKeyPrefixCompression,
                        enablePartialRead = sortedIndexReader.block.enablePartialRead,
                        isPreNormalised = sortedIndexReader.block.isPreNormalised,
                        headerSize = 0,
                        segmentMaxIndexEntrySize = sortedIndexReader.block.segmentMaxIndexEntrySize,
                        compressionInfo = None
                      )
                  ),
                valuesReader = valuesReader
              ) match {
                case Result.Matched(_, result, _) =>
                  Some(result)

                case Result.BehindStopped(keyValue) =>
                  collisions += keyValue
                  None

                case Result.BehindFetchNext(keyValue) =>
                  collisions += keyValue
                  None

                case Result.AheadOrNoneOrEnd(higher) =>
                  higher foreach {
                    higher =>
                      collisions += higher
                  }
                  None
              }
        )
      else
        search(
          key = key,
          reader = hashIndexReader,
          assertValue =
            (sortedIndexOffsetValue: Int) =>
              SortedIndexBlock.seekAndMatchOrSeek(
                matcher = matcher,
                fromOffset = sortedIndexOffsetValue,
                fullRead = false,
                indexReader = sortedIndexReader,
                valuesReader = valuesReader
              ) match {
                case Result.Matched(_, result, _) =>
                  Some(result)

                case Result.BehindStopped(previous) =>
                  collisions += previous
                  None

                case Result.AheadOrNoneOrEnd(higher) =>
                  higher foreach {
                    higher =>
                      collisions += higher
                  }
                  None
              }
        )

    result match {
      case Some(got) =>
        HashIndexSearchResult.Some(got)

      case None =>
        //if hashIndex did not successfully return a valid key-value then return the nearest lowest from the currently read
        //key-value from hashIndex.
        if (collisions.size <= 1) {
          collisions.headOption match {
            case Some(head) =>
              //println(s"Hash index's lowest 1: ${lowerKeyValues.head.key.readInt()}")
              if (keyOrder.gt(head.key, key))
                HashIndexSearchResult.None(None, Some(head))
              else
                HashIndexSearchResult.None(Some(head), None)

            case None =>
              HashIndexSearchResult.none
          }
        } else {
          val (lower, higher) = collisions.partition(keyValue => keyOrder.lt(keyValue.key, key))

          val lowest =
            if (lower.size <= 1)
              lower.headOption
            else
              lower.sorted(Ordering.by[Persistent.Partial, Slice[Byte]](_.key)).lastOption

          val highest =
            if (higher.size <= 1)
              higher.headOption
            else
              higher.sorted(Ordering.by[Persistent.Partial, Slice[Byte]](_.key)).headOption

          //println(s"Hash index's lowest 2: ${nearest.key.readInt()}")
          HashIndexSearchResult.None(lowest, highest)
        }
    }
  }

  implicit object HashIndexBlockOps extends BlockOps[HashIndexBlock.Offset, HashIndexBlock] {
    override def updateBlockOffset(block: HashIndexBlock, start: Int, size: Int): HashIndexBlock =
      block.copy(offset = createOffset(start = start, size = size))

    override def createOffset(start: Int, size: Int): Offset =
      HashIndexBlock.Offset(start = start, size = size)

    override def readBlock(header: Block.Header[Offset]): HashIndexBlock =
      HashIndexBlock.read(header)
  }
}

private[core] case class HashIndexBlock(offset: HashIndexBlock.Offset,
                                        compressionInfo: Option[Block.CompressionInfo],
                                        maxProbe: Int,
                                        copyIndex: Boolean,
                                        minimumCRC: Long,
                                        hit: Int,
                                        miss: Int,
                                        writeAbleLargestValueSize: Int,
                                        headerSize: Int,
                                        allocatedBytes: Int) extends Block[HashIndexBlock.Offset] {
  val bytesToReadPerIndex = writeAbleLargestValueSize + 1 //+1 to read header/marker 0 byte.

  val isCompressed = compressionInfo.isDefined

  def isPerfect =
    miss == 0
}