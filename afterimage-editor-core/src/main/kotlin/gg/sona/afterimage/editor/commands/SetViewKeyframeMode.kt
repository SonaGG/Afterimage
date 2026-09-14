package gg.sona.afterimage.editor.commands

import gg.sona.afterimage.camera.track.SegmentMode
import gg.sona.afterimage.editor.EditorCommand
import gg.sona.afterimage.editor.EditorProject

class SetViewKeyframeMode(private val times: Set<Long>, private val mode: SegmentMode) : EditorCommand {

    private val previous = HashMap<Long, SegmentMode>()

    override val label: String get() = "Set view interpolation"

    override fun apply(project: EditorProject) {
        previous.clear()
        for (time in times) {
            val existing = project.views.at(time) ?: continue
            previous[time] = existing.mode
            project.views.update(time) { it.copy(mode = mode) }
        }
    }

    override fun revert(project: EditorProject) {
        for ((time, mode) in previous) project.views.update(time) { it.copy(mode = mode) }
    }
}
