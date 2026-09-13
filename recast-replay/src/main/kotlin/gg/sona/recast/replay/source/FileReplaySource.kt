package gg.sona.recast.replay.source

import gg.sona.recast.format.RecastReader
import gg.sona.recast.format.RecordingHeader
import gg.sona.recast.format.SegmentInfo
import gg.sona.recast.net.CapturedPacket
import java.nio.file.Path

class FileReplaySource(private val reader: RecastReader, cacheSegments: Int = 64) : ReplaySource {
    private val cache = object : LinkedHashMap<Int, List<CapturedPacket>>(cacheSegments, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, List<CapturedPacket>>): Boolean =
            size > cacheSegments
    }

    override val header: RecordingHeader get() = reader.header

    override val segments: List<SegmentInfo> get() = reader.segments

    override val startNanos: Long get() = reader.startNanos

    override val endNanos: Long get() = reader.endNanos

    override fun packets(segmentIndex: Int): List<CapturedPacket> {
        synchronized(cache) {
            cache[segmentIndex]?.let { return it }
        }
        val loaded = reader.readSegment(segments[segmentIndex])
        synchronized(cache) { cache[segmentIndex] = loaded }
        return loaded
    }

    override fun snapshotAtOrBefore(nanos: Long): SegmentInfo? = reader.snapshotAtOrBefore(nanos)

    override fun close() = reader.close()

    companion object {
        fun open(path: Path): FileReplaySource = FileReplaySource(RecastReader.open(path))
    }
}