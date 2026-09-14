package gg.sona.afterimage.editor.commands

import gg.sona.afterimage.editor.EditorCommand
import gg.sona.afterimage.editor.EditorProject

class MovePoseKeyframe(private val entityId: Int, private val fromNanos: Long, private val toNanos: Long) :
    EditorCommand {
    override val label: String get() = "Move pose keyframe"

    override fun apply(project: EditorProject) {
        project.poses[entityId]?.move(fromNanos, toNanos)
    }

    override fun revert(project: EditorProject) {
        project.poses[entityId]?.move(toNanos, fromNanos)
    }
}
