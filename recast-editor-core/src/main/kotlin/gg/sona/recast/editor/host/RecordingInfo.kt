package gg.sona.recast.editor.host

data class RecordingInfo(
    val durationNanos: Long,
    val startEpochMillis: Long,
    val player: String?,
    val server: String?,
    val title: String?,
    val sizeBytes: Long,
)
