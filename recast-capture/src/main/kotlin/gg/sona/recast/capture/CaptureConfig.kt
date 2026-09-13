package gg.sona.recast.capture

import gg.sona.recast.core.time.Nanos
import gg.sona.recast.format.SegmentCompressor
import gg.sona.recast.format.WriterOptions
import gg.sona.recast.format.compressor.ZstdCompressor

data class CaptureConfig(
    val keyframeIntervalNanos: Long = Nanos.ofSeconds(10),
    val ringCapacity: Int = 1 shl 16,
    val compressor: SegmentCompressor = ZstdCompressor(),
    val writerOptions: WriterOptions = WriterOptions(compressor = compressor),
    val threadName: String = "recast-capture",
    val reorderWindowNanos: Long = 4_000_000L,
)