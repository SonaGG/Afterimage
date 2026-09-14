package gg.sona.recast.editor.commands

import gg.sona.recast.camera.Easing
import gg.sona.recast.camera.track.Keyframe
import gg.sona.recast.camera.track.SegmentMode
import gg.sona.recast.editor.EditorCommand
import gg.sona.recast.editor.EditorProject
import gg.sona.recast.editor.PackState

class SetPackKeyframe(val nanos: Long, val state: PackState) : EditorCommand {

    private var previous: Keyframe<PackState>? = null

    override val label: String get() = "Set texture pack keyframe"

    override fun apply(project: EditorProject) {
        previous = project.packs.at(nanos)
        project.packs.set(Keyframe(nanos, state, Easing.LINEAR, SegmentMode.HOLD))
    }

    override fun revert(project: EditorProject) {
        project.packs.remove(nanos)
        previous?.let { project.packs.set(it) }
    }

    override fun mergeWith(next: EditorCommand): EditorCommand? =
        if (next is SetPackKeyframe && next.nanos == nanos) SetPackKeyframe(nanos, next.state).also { it.previous = previous } else null
}
