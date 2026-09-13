package gg.sona.recast.editor

import java.util.*

data class Moment(
    val id: UUID,
    val nanos: Long,
    val endNanos: Long,
    val peakNanos: Long,
    val label: String,
    val kind: MomentKind,
    val score: Double,
    val origin: MomentOrigin,
    val entityId: Int,
) {
    val durationNanos: Long get() = endNanos - nanos

    fun contains(time: Long): Boolean = time in nanos..endNanos
}
