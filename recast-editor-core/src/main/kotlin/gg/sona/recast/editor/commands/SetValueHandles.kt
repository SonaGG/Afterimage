package gg.sona.recast.editor.commands

import gg.sona.recast.camera.track.Keyframe
import gg.sona.recast.editor.EditorCommand
import gg.sona.recast.editor.EditorProject
import gg.sona.recast.editor.ValueLane

class SetValueHandles(
    private val lane: ValueLane,
    private val nanos: Long,
    private val handleIn: Double?,
    private val handleOut: Double?,
) : EditorCommand {

    private var previous: Keyframe<Double>? = null

    override val label: String get() = "Adjust ${lane.label.lowercase()} handles"

    override fun apply(project: EditorProject) {
        val track = project.valueTrack(lane)
        previous = track.at(nanos) ?: return
        track.update(nanos) { it.copy(handleIn = handleIn, handleOut = handleOut) }
    }

    override fun revert(project: EditorProject) {
        val track = project.valueTrack(lane)
        previous?.let { track.set(it) }
    }

    override fun mergeWith(next: EditorCommand): EditorCommand? =
        if (next is SetValueHandles && next.lane == lane && next.nanos == nanos) SetValueHandles(
            lane,
            nanos,
            next.handleIn,
            next.handleOut
        ).also { it.previous = previous } else null
}
