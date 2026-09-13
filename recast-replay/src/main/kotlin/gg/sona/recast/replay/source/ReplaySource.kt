package gg.sona.recast.replay.source

import gg.sona.recast.format.RecordingHeader
import gg.sona.recast.format.SegmentInfo
import gg.sona.recast.net.CapturedPacket

interface ReplaySource : AutoCloseable {
    val header: RecordingHeader
    val segments: List<SegmentInfo>
    val startNanos: Long
    val endNanos: Long

    fun packets(segmentIndex: Int): List<CapturedPacket>

    fun snapshotAtOrBefore(nanos: Long): SegmentInfo?

    val durationNanos: Long get() = endNanos - startNanos
}