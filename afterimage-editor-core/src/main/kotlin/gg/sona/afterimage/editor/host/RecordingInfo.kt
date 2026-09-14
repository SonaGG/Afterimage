package gg.sona.afterimage.editor.host

data class RecordingInfo(
    val durationNanos: Long,
    val startEpochMillis: Long,
    val player: String?,
    val server: String?,
    val title: String?,
    val sizeBytes: Long,
)
