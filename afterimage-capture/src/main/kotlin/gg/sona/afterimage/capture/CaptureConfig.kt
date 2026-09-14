package gg.sona.afterimage.capture

import gg.sona.afterimage.core.time.Nanos
import gg.sona.afterimage.format.SegmentCompressor
import gg.sona.afterimage.format.WriterOptions
import gg.sona.afterimage.format.compressor.ZstdCompressor

data class CaptureConfig(
    val keyframeIntervalNanos: Long = Nanos.ofSeconds(10),
    val ringCapacity: Int = 1 shl 16,
    val compressor: SegmentCompressor = ZstdCompressor(),
    val writerOptions: WriterOptions = WriterOptions(compressor = compressor),
    val threadName: String = "afterimage-capture",
    val reorderWindowNanos: Long = 4_000_000L,
)