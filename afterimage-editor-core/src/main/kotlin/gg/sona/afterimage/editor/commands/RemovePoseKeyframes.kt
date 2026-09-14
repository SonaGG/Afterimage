package gg.sona.afterimage.editor.commands

import gg.sona.afterimage.camera.track.Keyframe
import gg.sona.afterimage.editor.EditorCommand
import gg.sona.afterimage.editor.EditorProject
import gg.sona.afterimage.editor.pose.BodyPose

class RemovePoseKeyframes(private val entityId: Int, private val times: Set<Long>) : EditorCommand {

    private var removed: List<Keyframe<BodyPose>> = emptyList()

    override val label: String get() = if (times.size == 1) "Delete pose keyframe" else "Delete ${times.size} pose keyframes"

    override fun apply(project: EditorProject) {
        val track = project.poses[entityId] ?: return
        removed = times.mapNotNull { track.at(it) }
        times.forEach { track.remove(it) }
        if (track.isEmpty) project.poses.remove(entityId)
    }

    override fun revert(project: EditorProject) {
        val track = project.poseTrack(entityId)
        removed.forEach { track.set(it) }
    }
}
