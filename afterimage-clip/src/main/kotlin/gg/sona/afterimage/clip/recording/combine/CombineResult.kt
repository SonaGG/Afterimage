package gg.sona.afterimage.clip.recording.combine

import java.nio.file.Path

class CombineResult(
    val path: Path,
    val packets: Long,
    val keyframes: Int,
    val durationNanos: Long,
    val segmentLengths: List<Long> = emptyList()
)