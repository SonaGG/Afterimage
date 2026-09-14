package gg.sona.afterimage.editor.imgui

import gg.sona.afterimage.clip.Clip
import gg.sona.afterimage.clip.bake.BakeJob
import gg.sona.afterimage.clip.bake.ClipBaker
import gg.sona.afterimage.editor.EditorSession
import gg.sona.afterimage.editor.commands.SetInOutPoints
import gg.sona.afterimage.render.ExportRequest
import gg.sona.afterimage.render.ExportSettings
import gg.sona.afterimage.render.ExportTarget

object ClipActions {
    fun bake(context: EditorContext, clip: Clip) {
        val backend = context.exports
        if (backend == null) {
            context.status("Baking is unavailable in this session")
            return
        }
        val output = context.host.bakesDirectory.resolve("${safeName(clip.title)}-${clip.id.toString().take(8)}.afterimage")
        backend.queue().submit(BakeJob(ClipBaker(), clip, output))
        context.status("Baking ${clip.title} to ${output.fileName}  (see Export panel for progress)")
    }

    fun export(context: EditorContext, clip: Clip) {
        val backend = context.exports
        if (backend == null) {
            context.status("Exporting is unavailable in this session")
            return
        }
        val target = if (backend.ffmpegAvailable) ExportTarget.VIDEO else ExportTarget.PNG_SEQUENCE
        val extension = if (target == ExportTarget.VIDEO) "mp4" else "png"
        val settings = ExportSettings(
            width = backend.windowWidth,
            height = backend.windowHeight,
            fps = context.timeline.renderFps,
            startNanos = clip.startNanos,
            endNanos = clip.endNanos,
            output = context.host.exportsDirectory.resolve(
                "${safeName(clip.title)}-${
                    clip.id.toString().take(8)
                }.$extension"
            ),
        )
        backend.submit(ExportRequest(clip.title, settings, target))
        context.status("Exporting ${clip.title}  (see Export panel for progress)")
    }

    fun play(session: EditorSession, clip: Clip) {
        val replay = session.replay ?: return
        session.execute(SetInOutPoints(clip.startNanos, clip.endNanos))
        replay.seek(clip.startNanos)
        replay.play()
    }

    fun safeName(title: String): String = title.replace(Regex("[^A-Za-z0-9-_ ]"), "_").trim().ifEmpty { "clip" }
}
