package gg.sona.recast.clip.recording.combine

import gg.sona.recast.clip.export.ExportJob
import java.nio.file.Path

class CombineJob(
    private val combiner: RecordingCombiner,
    private val sources: List<Path>,
    private val output: Path,
    private val ranges: List<LongRange?> = emptyList()
) :
    ExportJob {
    override val name: String get() = "combine ${sources.size} recordings"

    var result: CombineResult? = null
        private set

    override fun run(progress: (Double) -> Unit): Path =
        combiner.combine(sources, output, ranges, progress).also { result = it }.path
}
