package gg.sona.recast.editor

import java.nio.file.Path

data class ProjectSummary(
    val path: Path,
    val name: String,
    val segments: List<Segment>,
    val modifiedEpochMillis: Long,
    val keyframes: Int,
    val clips: Int,
    val markers: Int
) {
    val isSequence: Boolean get() = segments.size > 1 || segments.any { it.inNanos > 0L || it.outNanos > 0L }

    val lengthNanos: Long
        get() = segments.sumOf {
            if (it.lengthNanos > 0L) it.lengthNanos else maxOf(
                0L,
                it.outNanos - it.inNanos
            )
        }
}
