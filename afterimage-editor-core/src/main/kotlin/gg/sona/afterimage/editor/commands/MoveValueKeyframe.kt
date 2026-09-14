package gg.sona.afterimage.editor.commands

import gg.sona.afterimage.camera.track.Keyframe
import gg.sona.afterimage.editor.EditorCommand
import gg.sona.afterimage.editor.EditorProject
import gg.sona.afterimage.editor.ValueLane

class MoveValueKeyframe(private val lane: ValueLane, private val fromNanos: Long, private val toNanos: Long) :
    EditorCommand {
    private var overwritten: Keyframe<Double>? = null

    override val label: String get() = "Move ${lane.label.lowercase()} keyframe"

    override fun apply(project: EditorProject) {
        val track = project.valueTrack(lane)
        overwritten = if (toNanos != fromNanos) track.at(toNanos) else null
        track.move(fromNanos, maxOf(0L, toNanos))
    }

    override fun revert(project: EditorProject) {
        val track = project.valueTrack(lane)
        track.move(maxOf(0L, toNanos), fromNanos)
        overwritten?.let { track.set(it) }
    }

    override fun mergeWith(next: EditorCommand): EditorCommand? =
        if (next is MoveValueKeyframe && next.lane == lane && next.fromNanos == toNanos) MoveValueKeyframe(
            lane,
            fromNanos,
            next.toNanos
        ).also { it.overwritten = overwritten } else null
}
