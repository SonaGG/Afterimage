package gg.sona.recast.format


data class SegmentInfo(
    val index: Int,
    val offset: Long,
    val kind: SegmentKind,
    val startNanos: Long,
    val endNanos: Long,
    val packetCount: Int,
    val rawLength: Int,
    val storedLength: Int,
) {
    val isSnapshot: Boolean get() = kind == SegmentKind.SNAPSHOT

    val bodyOffset: Long get() = offset + RecastFormat.SEGMENT_HEADER_BYTES
}
