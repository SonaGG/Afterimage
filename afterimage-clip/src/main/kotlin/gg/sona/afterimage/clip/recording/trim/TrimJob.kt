package gg.sona.afterimage.clip.recording.trim

import gg.sona.afterimage.clip.export.ExportJob
import java.nio.file.Path

class TrimJob(
    private val trimmer: RecordingTrimmer,
    private val source: Path,
    private val output: Path,
    private val startNanos: Long,
    private val endNanos: Long
) :
    ExportJob {
    override val name: String get() = "trim ${source.fileName}"

    override fun run(progress: (Double) -> Unit): Path =
        trimmer.trim(source, output, startNanos, endNanos, progress).path
}
