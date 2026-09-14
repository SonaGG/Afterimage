package gg.sona.afterimage.clip

import gg.sona.afterimage.camera.CameraPath
import java.nio.file.Path
import java.util.*

data class Clip(
    val id: UUID,
    val recording: Path,
    val sessionId: UUID,
    val startNanos: Long,
    val endNanos: Long,
    val title: String,
    val tags: Set<String> = emptySet(),
    val origin: ClipOrigin = ClipOrigin.MANUAL,
    val createdAtEpochMillis: Long = System.currentTimeMillis(),
    val cameraOverride: CameraPath? = null,
    val note: String = "",
) {
    init {
        require(endNanos >= startNanos) { "clip end must not precede start" }
    }

    val durationNanos: Long get() = endNanos - startNanos

    fun trimmed(newStartNanos: Long, newEndNanos: Long): Clip = copy(startNanos = newStartNanos, endNanos = newEndNanos)

    fun tagged(vararg extra: String): Clip = copy(tags = tags + extra)

    fun contains(nanos: Long): Boolean = nanos in startNanos..endNanos

    companion object {
        fun around(
            recording: Path,
            sessionId: UUID,
            momentNanos: Long,
            preRollNanos: Long,
            postRollNanos: Long,
            title: String,
            origin: ClipOrigin
        ): Clip =
            Clip(
                UUID.randomUUID(),
                recording,
                sessionId,
                maxOf(0L, momentNanos - preRollNanos),
                momentNanos + postRollNanos,
                title,
                origin = origin
            )
    }
}
