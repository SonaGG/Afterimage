package gg.sona.recast.editor.commands

import gg.sona.recast.camera.Easing
import gg.sona.recast.camera.track.Keyframe
import gg.sona.recast.camera.track.SegmentMode
import gg.sona.recast.editor.EditorCommand
import gg.sona.recast.editor.EditorProject
import gg.sona.recast.editor.ViewState

class SetViewKeyframe(
    val nanos: Long,
    val view: ViewState,
    val mode: SegmentMode = SegmentMode.CATMULL_ROM,
    val easing: Easing = Easing.LINEAR,
) : EditorCommand {

    private var previous: Keyframe<ViewState>? = null

    override val label: String get() = "Set view keyframe"

    override fun apply(project: EditorProject) {
        previous = project.views.at(nanos)
        project.views.set(Keyframe(nanos, view, easing, mode))
    }

    override fun revert(project: EditorProject) {
        project.views.remove(nanos)
        previous?.let { project.views.set(it) }
    }
}
