package gg.sona.afterimage.clip.export

class ExportProgress(
    val progress: (Double) -> Unit,
    val detail: (String) -> Unit = {},
    val warn: (String) -> Unit = {},
)
