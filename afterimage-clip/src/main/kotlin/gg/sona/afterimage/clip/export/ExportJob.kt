package gg.sona.afterimage.clip.export

import java.nio.file.Path

interface ExportJob {
    val name: String

    fun run(progress: (Double) -> Unit): Path
    fun run(report: ExportProgress): Path = run(report.progress)

    fun cancel() {}
}