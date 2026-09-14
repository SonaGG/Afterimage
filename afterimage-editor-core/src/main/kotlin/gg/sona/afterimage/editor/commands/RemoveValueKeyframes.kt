package gg.sona.afterimage.editor.commands

import gg.sona.afterimage.camera.track.Keyframe
import gg.sona.afterimage.editor.EditorCommand
import gg.sona.afterimage.editor.EditorProject
import gg.sona.afterimage.editor.ValueKey
import gg.sona.afterimage.editor.ValueLane

class RemoveValueKeyframes(private val keys: Set<ValueKey>) : EditorCommand {

    private var removed: List<Pair<ValueLane, Keyframe<Double>>> = emptyList()

    override val label: String get() = if (keys.size == 1) "Delete ${keys.first().lane.label.lowercase()} keyframe" else "Delete ${keys.size} keyframes"

    override fun apply(project: EditorProject) {
        removed = keys.mapNotNull { key -> project.valueTrack(key.lane).at(key.nanos)?.let { key.lane to it } }
        keys.forEach { project.valueTrack(it.lane).remove(it.nanos) }
    }

    override fun revert(project: EditorProject) {
        removed.forEach { (lane, frame) -> project.valueTrack(lane).set(frame) }
    }
}
