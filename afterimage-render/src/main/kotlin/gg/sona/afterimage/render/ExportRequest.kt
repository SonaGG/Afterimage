package gg.sona.afterimage.render


data class ExportRequest(
    val title: String,
    val settings: ExportSettings,
    val target: ExportTarget,
)
