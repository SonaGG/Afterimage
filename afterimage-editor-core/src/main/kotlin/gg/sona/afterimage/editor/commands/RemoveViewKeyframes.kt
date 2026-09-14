package gg.sona.afterimage.editor.commands

import gg.sona.afterimage.camera.track.Keyframe
import gg.sona.afterimage.editor.EditorCommand
import gg.sona.afterimage.editor.EditorProject
import gg.sona.afterimage.editor.ViewState

class RemoveViewKeyframes(private val times: Set<Long>) : EditorCommand {

    private var removed: List<Keyframe<ViewState>> = emptyList()

    override val label: String get() = "Delete view keyframe"

    override fun apply(project: EditorProject) {
        removed = times.mapNotNull { project.views.at(it) }
        times.forEach { project.views.remove(it) }
    }

    override fun revert(project: EditorProject) {
        removed.forEach { project.views.set(it) }
    }
}
