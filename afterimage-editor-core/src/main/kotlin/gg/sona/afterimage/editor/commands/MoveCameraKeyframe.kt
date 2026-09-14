package gg.sona.afterimage.editor.commands

import gg.sona.afterimage.editor.EditorCommand
import gg.sona.afterimage.editor.EditorProject

class MoveCameraKeyframe(private val fromNanos: Long, private val toNanos: Long) : EditorCommand {

    override val label: String get() = "Move keyframe"

    override fun apply(project: EditorProject) {
        project.camera.position.move(fromNanos, toNanos)
        project.camera.rotation.move(fromNanos, toNanos)
        project.camera.fov.move(fromNanos, toNanos)
    }

    override fun revert(project: EditorProject) {
        project.camera.position.move(toNanos, fromNanos)
        project.camera.rotation.move(toNanos, fromNanos)
        project.camera.fov.move(toNanos, fromNanos)
    }

    override fun mergeWith(next: EditorCommand): EditorCommand? =
        if (next is MoveCameraKeyframe && next.fromNanos == toNanos) MoveCameraKeyframe(
            fromNanos,
            next.toNanos
        ) else null
}
