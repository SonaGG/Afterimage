package gg.sona.afterimage.clip.recording.compact

import gg.sona.afterimage.clip.export.ExportJob
import gg.sona.afterimage.clip.export.ExportProgress
import java.nio.file.Path

class CompactJob(private val compactor: RecordingCompactor, private val path: Path) : ExportJob {
    override val name: String get() = "compact ${path.fileName.toString().substringBeforeLast('.')}"

    var result: CompactResult? = null
        private set

    override fun run(progress: (Double) -> Unit): Path = run(ExportProgress(progress) {})
    override fun run(report: ExportProgress): Path {
        val outcome = compactor.compactInPlace(path) { report.progress(it * 0.95) }
        result = outcome
        report.detail(
            String.format(
                "%.1f MB -> %.1f MB (%.1fx)",
                outcome.inputBytes / 1048576.0,
                outcome.outputBytes / 1048576.0,
                outcome.ratio
            )
        )
        return path
    }
}
