package gg.sona.recast.editor.commands

import gg.sona.recast.camera.CameraPathKey
import gg.sona.recast.editor.EditorCommand
import gg.sona.recast.editor.EditorProject

class ClearCameraPath : EditorCommand {

    private var removed: List<CameraPathKey> = emptyList()

    override val label: String get() = "Clear camera path"

    override fun apply(project: EditorProject) {
        removed = project.camera.snapshots()
        removed.forEach { project.camera.removeKeyframe(it.timeNanos) }
    }

    override fun revert(project: EditorProject) {
        removed.forEach { project.camera.restore(it) }
    }
}
