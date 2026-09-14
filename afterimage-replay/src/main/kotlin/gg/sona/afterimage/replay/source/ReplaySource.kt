package gg.sona.afterimage.replay.source

import gg.sona.afterimage.format.RecordingHeader
import gg.sona.afterimage.format.SegmentInfo
import gg.sona.afterimage.net.CapturedPacket

interface ReplaySource : AutoCloseable {
    val header: RecordingHeader
    val segments: List<SegmentInfo>
    val startNanos: Long
    val endNanos: Long

    fun packets(segmentIndex: Int): List<CapturedPacket>

    fun snapshotAtOrBefore(nanos: Long): SegmentInfo?

    val durationNanos: Long get() = endNanos - startNanos
}