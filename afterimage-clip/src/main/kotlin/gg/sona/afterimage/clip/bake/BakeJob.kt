package gg.sona.afterimage.clip.bake

import gg.sona.afterimage.clip.Clip
import gg.sona.afterimage.clip.export.ExportJob
import java.nio.file.Path

class BakeJob(private val baker: ClipBaker, private val clip: Clip, private val output: Path) : ExportJob {
    override val name: String get() = "bake ${clip.title}"
    override fun run(progress: (Double) -> Unit): Path = baker.bake(clip, output, progress).path
}
