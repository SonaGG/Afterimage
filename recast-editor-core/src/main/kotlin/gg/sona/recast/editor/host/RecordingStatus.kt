package gg.sona.recast.editor.host

import java.nio.file.Path

data class RecordingStatus(
    val connected: Boolean,
    val recording: Boolean,
    val path: Path?,
    val packets: Long,
    val bytes: Long,
    val keyframes: Int,
    val overflowEvents: Long,
    val elapsedNanos: Long,
)
