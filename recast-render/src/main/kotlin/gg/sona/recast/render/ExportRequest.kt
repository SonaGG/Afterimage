package gg.sona.recast.render


data class ExportRequest(
    val title: String,
    val settings: ExportSettings,
    val target: ExportTarget,
)
