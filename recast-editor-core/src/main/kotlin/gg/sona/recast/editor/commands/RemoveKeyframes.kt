package gg.sona.recast.editor.commands

import gg.sona.recast.camera.CameraPathKey
import gg.sona.recast.editor.EditorCommand
import gg.sona.recast.editor.EditorProject

class RemoveKeyframes(private val times: Set<Long>) : EditorCommand {
    private var removed: List<CameraPathKey> = emptyList()

    override val label: String get() = if (times.size == 1) "Delete keyframe" else "Delete ${times.size} keyframes"

    override fun apply(project: EditorProject) {
        removed = times.mapNotNull { project.camera.snapshot(it) }
        times.forEach { project.camera.removeKeyframe(it) }
    }

    override fun revert(project: EditorProject) {
        for (key in removed) project.camera.restore(key)
    }
}
