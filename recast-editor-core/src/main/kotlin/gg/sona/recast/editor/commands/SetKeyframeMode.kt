package gg.sona.recast.editor.commands

import gg.sona.recast.camera.track.SegmentMode
import gg.sona.recast.editor.EditorCommand
import gg.sona.recast.editor.EditorProject

class SetKeyframeMode(private val times: Set<Long>, private val mode: SegmentMode) : EditorCommand {

    private val previous = HashMap<Long, SegmentMode>()

    override val label: String get() = "Set interpolation"

    override fun apply(project: EditorProject) {
        previous.clear()
        for (time in times) {
            val existing = project.camera.keyframeAt(time) ?: continue
            previous[time] = existing.mode
            project.camera.setMode(time, mode)
        }
    }

    override fun revert(project: EditorProject) {
        for ((time, mode) in previous) project.camera.setMode(time, mode)
    }
}
