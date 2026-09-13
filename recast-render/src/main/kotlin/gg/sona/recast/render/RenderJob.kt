package gg.sona.recast.render

import gg.sona.recast.clip.export.ExportJob
import gg.sona.recast.clip.export.ExportProgress
import java.nio.file.Path

class RenderJob(private val pipeline: ExportPipeline, override val name: String) : ExportJob {
    override fun run(progress: (Double) -> Unit): Path = pipeline.run(progress)

    override fun run(report: ExportProgress): Path = pipeline.run(report)

    override fun cancel() {
        pipeline.cancelled = true
    }
}
