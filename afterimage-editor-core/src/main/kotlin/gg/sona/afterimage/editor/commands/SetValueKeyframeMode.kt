package gg.sona.afterimage.editor.commands

import gg.sona.afterimage.camera.track.SegmentMode
import gg.sona.afterimage.editor.EditorCommand
import gg.sona.afterimage.editor.EditorProject
import gg.sona.afterimage.editor.ValueKey

class SetValueKeyframeMode(private val keys: Set<ValueKey>, private val mode: SegmentMode) : EditorCommand {

    private val previous = HashMap<ValueKey, SegmentMode>()

    override val label: String get() = "Set interpolation"

    override fun apply(project: EditorProject) {
        previous.clear()
        for (key in keys) {
            val track = project.valueTrack(key.lane)
            val existing = track.at(key.nanos) ?: continue
            previous[key] = existing.mode
            track.update(key.nanos) { it.copy(mode = mode) }
        }
    }

    override fun revert(project: EditorProject) {
        for ((key, mode) in previous) project.valueTrack(key.lane).update(key.nanos) { it.copy(mode = mode) }
    }
}
