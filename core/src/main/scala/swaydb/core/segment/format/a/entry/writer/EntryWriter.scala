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

package swaydb.core.segment.format.a.entry.writer

import swaydb.core.data.{Memory, MemoryOptional}
import swaydb.core.segment.format.a.entry.id.BaseEntryId.BaseEntryIdFormat
import swaydb.core.segment.format.a.entry.id.{BaseEntryIdFormatA, KeyValueId, MemoryToKeyValueIdBinder}
import swaydb.core.util.Bytes
import swaydb.data.slice.Slice
import swaydb.data.util.ByteSizeOf

private[core] object EntryWriter {

  object Builder {
    def apply(enablePrefixCompression: Boolean,
              prefixCompressKeysOnly: Boolean,
              compressDuplicateValues: Boolean,
              enableAccessPositionIndex: Boolean,
              bytes: Slice[Byte]): Builder =
      new Builder(
        enablePrefixCompression = enablePrefixCompression,
        prefixCompressKeysOnly = prefixCompressKeysOnly,
        compressDuplicateValues = compressDuplicateValues,
        isValueFullyCompressed = false,
        enableAccessPositionIndex = enableAccessPositionIndex,
        bytes = bytes,
        startValueOffset = 0,
        endValueOffset = 0,
        accessPositionIndex = 0,
        previous = Memory.Null,
        isCurrentPrefixCompressed = false,
        _segmentHasPrefixCompression = false
      )
  }

  class Builder(val enablePrefixCompression: Boolean,
                val prefixCompressKeysOnly: Boolean,
                var compressDuplicateValues: Boolean,
                //this should be reset to false once the entry is written
                var isValueFullyCompressed: Boolean,
                val enableAccessPositionIndex: Boolean,
                val bytes: Slice[Byte],
                var startValueOffset: Int,
                var endValueOffset: Int,
                var accessPositionIndex: Int,
                var previous: MemoryOptional,
                //this should be reset to false once the entry is written
                var isCurrentPrefixCompressed: Boolean,
                private var _segmentHasPrefixCompression: Boolean) {

    def segmentHasPrefixCompression = _segmentHasPrefixCompression

    def setSegmentHasPrefixCompression() = {
      //this flag is an indicator for SortedIndex that current write was prefix compressed.
      //this should be reset with every write by SortedIndex.
      this.isCurrentPrefixCompressed = true
      this._segmentHasPrefixCompression = true
    }

    def nextStartValueOffset: Int =
      if (endValueOffset == 0)
        0
      else
        endValueOffset + 1
  }

  private val tailBytes =
    KeyValueId.maxKeyValueIdByteSize + //keyValueId
      ByteSizeOf.varLong + //deadline
      ByteSizeOf.varInt + //valueOffset
      ByteSizeOf.varInt + //valueLength
      ByteSizeOf.int + //timeLength
      ByteSizeOf.varLong //time

  /**
   * Format - keySize|key|keyValueId|accessIndex?|deadline|valueOffset|valueLength|time
   *
   * Returns the index bytes and value bytes for the key-value and also the used
   * value offset information for writing the next key-value.
   *
   * Each key also has a meta block which can be used to backward compatibility to store
   * more information for that key in the future that does not fit the current key format.
   *
   * Currently all keys are being stored under EmptyMeta.
   *
   * Note: No extra bytes are required to differentiate between a key that has meta or no meta block.
   *
   * @param binder [[BaseEntryIdFormat]] for this key-value's type.
   * @return indexEntry, valueBytes, valueOffsetBytes, nextValuesOffsetPosition
   */
  def write[T <: Memory](current: T,
                         builder: EntryWriter.Builder)(implicit binder: MemoryToKeyValueIdBinder[T],
                                                       timeWriter: TimeWriter,
                                                       valueWriter: ValueWriter,
                                                       keyWriter: KeyWriter,
                                                       deadlineWriter: DeadlineWriter): Unit =
    timeWriter.write(
      current = current,
      entryId = BaseEntryIdFormatA.format.start,
      builder = builder
    )

  def maxEntrySize(keySize: Int,
                   hasAccessIndexPosition: Boolean): Int =
    Bytes.sizeOfUnsignedInt(keySize) + //size of key
      keySize + //key itself
      (if (hasAccessIndexPosition) ByteSizeOf.varInt else 0) + //accessIndexPosition
      tailBytes

  def maxEntrySize(hasAccessIndexPosition: Boolean): Int =
    (if (hasAccessIndexPosition) ByteSizeOf.varInt else 0) + //accessIndexPosition
      tailBytes
}
