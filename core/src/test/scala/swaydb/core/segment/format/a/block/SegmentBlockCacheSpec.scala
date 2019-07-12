package swaydb.core.segment.format.a.block


import swaydb.core.CommonAssertions._
import swaydb.core.IOAssert._
import swaydb.core.RunThis._
import swaydb.core.TestData._
import swaydb.core.data.Value.{FromValue, RangeValue}
import swaydb.core.data._
import swaydb.core.io.reader.Reader
import swaydb.core.util.Benchmark
import swaydb.core.{TestBase, TestLimitQueues, TestTimer}
import swaydb.data.config.BlockIO
import swaydb.data.order.KeyOrder
import swaydb.data.slice.Slice
import swaydb.serializers.Default._
import swaydb.serializers._

class SegmentBlockCacheSpec extends TestBase {
  implicit val order = KeyOrder.default

  "read blocks" in {
    //one big azz test.
    //It create a Segment with randomly generated key-values and asserts all the caches.

    runThis(100.times) {

      val keyValues =
        randomizedKeyValues(100, addPut = true, addRandomGroups = false)
          .updateStats(
            binarySearchIndexConfig =
              BinarySearchIndex.Config(
                enabled = true,
                minimumNumberOfKeys = 0,
                fullIndex = true,
                blockIO = blockInfo => BlockIO.SynchronisedIO(cacheOnAccess = blockInfo.isCompressed),
                compressions = _ => randomCompressions()
              ),
            sortedIndexConfig =
              SortedIndex.Config(
                blockIO = blockInfo => BlockIO.SynchronisedIO(cacheOnAccess = blockInfo.isCompressed),
                prefixCompressionResetCount = 3,
                enableAccessPositionIndex = true,
                compressions = _ => randomCompressions()
              )
          )

      val segmentCompression = randomCompressionsOrEmpty()

      //create an open block and a closed block. SegmentBlockCache cannot be created on opened block.
      //Open block is used to assert the decompressed bytes got from closed block.
      val openSegment: SegmentBlock.Open = SegmentBlock.writeOpen(keyValues, Int.MaxValue, SegmentBlock.Config.random).get
      val closedSegment: SegmentBlock.Closed = SegmentBlock.writeClosed(keyValues, Int.MaxValue, SegmentBlock.Config.random).get

      //closedSegments are compressed and the sizes will not match
      if (segmentCompression.isEmpty)
        openSegment.segmentSize shouldBe closedSegment.segmentSize
      else
        closedSegment.segmentBytes should have size 1 //compressed bytes.

      //but both will not have the same header bytes because openSegment is not compressed and does not have compression info.
      if (segmentCompression.isEmpty)
        openSegment.headerBytes shouldBe closedSegment.segmentBytes.head.take(openSegment.headerBytes.size)
      else
        openSegment.headerBytes.head shouldBe closedSegment.segmentBytes.head.head //if compression exists check if first byte matches which is header size.

      openSegment.headerBytes.isOriginalFullSlice shouldBe true //bytes should be full.
      closedSegment.segmentBytes.head.isFull shouldBe true //bytes should be full.

      //initialise a block cache and run asserts
      val segmentBlockCache = getSegmentBlockCache(closedSegment)

      /**
        * One by one fetch everything from [[SegmentBlockCache]] and assert.
        */
      //on init, nothing is cached.
      segmentBlockCache.isCached shouldBe false

      //getSegmentBlock
      segmentBlockCache.segmentBlockCache.isCached shouldBe false
      val segmentBlock = segmentBlockCache.getSegmentBlock().get
      segmentBlockCache.segmentBlockCache.isCached shouldBe true
      segmentBlock.compressionInfo.isDefined shouldBe segmentCompression.nonEmpty //segment is not closed.
      segmentBlock.headerSize shouldBe openSegment.headerBytes.size
      //read the entire segment from blockedSegment. Compressed or uncompressed it should result in the same bytes as original.
      segmentBlockCache.createSegmentBlockReader().get.readRemaining().get shouldBe openSegment.segmentBytes.dropHead().flatten.toSlice
      segmentBlockCache.segmentBlockCache.isCached shouldBe true
      segmentBlockCache.segmentBlockReaderCache.isCached shouldBe segmentCompression.nonEmpty

      segmentBlockCache.footerCache.isCached shouldBe false
      val footer = segmentBlockCache.getFooter().get
      segmentBlockCache.footerCache.isCached shouldBe true
      footer.keyValueCount shouldBe keyValues.size
      footer.createdInLevel shouldBe Int.MaxValue


      //      val binarySearchIndexBytes = segmentBlockCache.createBinarySearchIndexReader().get.get.readRemaining()
      //
      //      println(binarySearchIndexBytes)

      //      keyValues foreach {
      //        keyValue =>
      //          BinarySearchIndex.search(
      //            key = keyValue.minKey,
      //            start = None,
      //            end = None,
      //            binarySearchIndexReader = segmentBlockCache.createBinarySearchIndexReader().get.get,
      //            sortedIndexReader = segmentBlockCache.createSortedIndexReader().get,
      //            valuesReader = segmentBlockCache.createValuesReader().get
      //          ).get shouldBe keyValue
      //      }
    }
  }
}