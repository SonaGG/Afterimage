package gg.sona.recast.editor.commands

import gg.sona.recast.camera.track.Keyframe
import gg.sona.recast.editor.EditorCommand
import gg.sona.recast.editor.EditorProject
import gg.sona.recast.editor.PackState

class MovePackKeyframe(private val fromNanos: Long, private val toNanos: Long) : EditorCommand {
    private var overwritten: Keyframe<PackState>? = null

    override val label: String get() = "Move texture pack keyframe"

    override fun apply(project: EditorProject) {
        overwritten = if (toNanos != fromNanos) project.packs.at(toNanos) else null
        project.packs.move(fromNanos, maxOf(0L, toNanos))
    }

    override fun revert(project: EditorProject) {
        project.packs.move(maxOf(0L, toNanos), fromNanos)
        overwritten?.let { project.packs.set(it) }
    }

    override fun mergeWith(next: EditorCommand): EditorCommand? =
        if (next is MovePackKeyframe && next.fromNanos == toNanos) MovePackKeyframe(fromNanos, next.toNanos).also { it.overwritten = overwritten } else null
}
