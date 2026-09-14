package gg.sona.afterimage.editor.commands

import gg.sona.afterimage.camera.track.Extrapolation
import gg.sona.afterimage.editor.EditorCommand
import gg.sona.afterimage.editor.EditorProject
import gg.sona.afterimage.editor.ValueLane

class SetTrackExtrapolation(
    private val lane: ValueLane?,
    private val pre: Extrapolation,
    private val post: Extrapolation,
) : EditorCommand {

    private var previousPre = Extrapolation.HOLD
    private var previousPost = Extrapolation.HOLD

    override val label: String get() = "Set extrapolation"

    override fun apply(project: EditorProject) {
        val lane = lane
        if (lane == null) {
            previousPre = project.camera.preExtrapolation
            previousPost = project.camera.postExtrapolation
            project.camera.preExtrapolation = pre
            project.camera.postExtrapolation = post
        } else {
            val track = project.valueTrack(lane)
            previousPre = track.preExtrapolation
            previousPost = track.postExtrapolation
            track.preExtrapolation = pre
            track.postExtrapolation = post
        }
    }

    override fun revert(project: EditorProject) {
        val lane = lane
        if (lane == null) {
            project.camera.preExtrapolation = previousPre
            project.camera.postExtrapolation = previousPost
        } else {
            val track = project.valueTrack(lane)
            track.preExtrapolation = previousPre
            track.postExtrapolation = previousPost
        }
    }
}
