package gg.sona.recast.editor.commands

import gg.sona.recast.camera.Easing
import gg.sona.recast.camera.track.Keyframe
import gg.sona.recast.camera.track.SegmentMode
import gg.sona.recast.editor.EditorCommand
import gg.sona.recast.editor.EditorProject
import gg.sona.recast.editor.ValueLane

class SetValueKeyframe(
    val lane: ValueLane,
    val nanos: Long,
    val value: Double,
    val mode: SegmentMode = SegmentMode.LINEAR,
    val easing: Easing = Easing.LINEAR
) : EditorCommand {

    private var previous: Keyframe<Double>? = null

    override val label: String get() = "Set ${lane.label.lowercase()} keyframe"

    override fun apply(project: EditorProject) {
        val track = project.valueTrack(lane)
        previous = track.at(nanos)
        track.set(Keyframe(nanos, value.coerceIn(lane.min, lane.max), easing, mode))
    }

    override fun revert(project: EditorProject) {
        val track = project.valueTrack(lane)
        track.remove(nanos)
        previous?.let { track.set(it) }
    }

    override fun mergeWith(next: EditorCommand): EditorCommand? =
        if (next is SetValueKeyframe && next.lane == lane && next.nanos == nanos) SetValueKeyframe(
            lane,
            nanos,
            next.value,
            next.mode,
            next.easing
        ).also { it.previous = previous } else null
}
