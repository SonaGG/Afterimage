package gg.sona.afterimage.editor.commands

import gg.sona.afterimage.camera.track.Keyframe
import gg.sona.afterimage.editor.EditorCommand
import gg.sona.afterimage.editor.EditorProject
import gg.sona.afterimage.editor.ViewState

class MoveViewKeyframe(private val fromNanos: Long, private val toNanos: Long) : EditorCommand {
    private var overwritten: Keyframe<ViewState>? = null

    override val label: String get() = "Move view keyframe"

    override fun apply(project: EditorProject) {
        overwritten = if (toNanos != fromNanos) project.views.at(toNanos) else null
        project.views.move(fromNanos, maxOf(0L, toNanos))
    }

    override fun revert(project: EditorProject) {
        project.views.move(maxOf(0L, toNanos), fromNanos)
        overwritten?.let { project.views.set(it) }
    }

    override fun mergeWith(next: EditorCommand): EditorCommand? =
        if (next is MoveViewKeyframe && next.fromNanos == toNanos) MoveViewKeyframe(
            fromNanos,
            next.toNanos
        ).also { it.overwritten = overwritten } else null
}
