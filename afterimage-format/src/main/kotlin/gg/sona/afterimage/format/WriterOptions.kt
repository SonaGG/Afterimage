package gg.sona.afterimage.format

import gg.sona.afterimage.core.time.Nanos
import gg.sona.afterimage.format.compressor.ZstdCompressor

data class WriterOptions(
    val compressor: SegmentCompressor = ZstdCompressor(),
    val blobCompressor: SegmentCompressor = ZstdCompressor(9),
    val snapshotCompressor: SegmentCompressor = ZstdCompressor(9),
    val maxSegmentRawBytes: Int = 1 shl 20,
    val maxSegmentPackets: Int = 4096,
    val maxSegmentDurationNanos: Long = Nanos.ofSeconds(5),
    val syncIntervalNanos: Long = Nanos.ofSeconds(5),
    val maxBlobSegmentRawBytes: Int = 4 shl 20,
    val dedupeChunks: Boolean = true,
)
