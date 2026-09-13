package gg.sona.recast.editor.commands

import gg.sona.recast.camera.Easing
import gg.sona.recast.camera.track.Keyframe
import gg.sona.recast.camera.track.SegmentMode
import gg.sona.recast.editor.EditorCommand
import gg.sona.recast.editor.EditorProject
import gg.sona.recast.editor.pose.BodyPose

class SetPoseKeyframe(
    val entityId: Int,
    val nanos: Long,
    val pose: BodyPose,
    val easing: Easing = Easing.LINEAR,
    val mode: SegmentMode = SegmentMode.LINEAR,
) : EditorCommand {

    private var previous: Keyframe<BodyPose>? = null

    override val label: String get() = if (pose.isEmpty) "Release pose" else "Set pose keyframe"

    override fun apply(project: EditorProject) {
        val track = project.poseTrack(entityId)
        previous = track.at(nanos)
        track.set(Keyframe(nanos, pose, easing, mode))
    }

    override fun revert(project: EditorProject) {
        val track = project.poseTrack(entityId)
        track.remove(nanos)
        previous?.let { track.set(it) }
        if (track.isEmpty) project.poses.remove(entityId)
    }

    override fun mergeWith(next: EditorCommand): EditorCommand? =
        if (next is SetPoseKeyframe && next.entityId == entityId && next.nanos == nanos) SetPoseKeyframe(
            entityId,
            nanos,
            next.pose,
            next.easing,
            next.mode
        ).also { it.previous = previous } else null
}
