package gg.sona.afterimage.render


class ExportLive(
    val settings: ExportSettings,
    val framesDone: Long,
    val frameCount: Long,
    val startedAtNanos: Long,
    val previewTexture: Int,
    val previewWidth: Int,
    val previewHeight: Int,
    val stage: String,
)
