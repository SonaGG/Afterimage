package gg.sona.recast.editor.commands

import gg.sona.recast.camera.CameraPose
import gg.sona.recast.camera.Easing
import gg.sona.recast.camera.Rotation
import gg.sona.recast.camera.track.Keyframe
import gg.sona.recast.camera.track.SegmentMode
import gg.sona.recast.editor.EditorCommand
import gg.sona.recast.editor.EditorProject
import org.joml.Vector3d

class SetCameraKeyframe(
    private val nanos: Long,
    private val pose: CameraPose,
    private val easing: Easing = Easing.LINEAR,
    private val mode: SegmentMode = SegmentMode.CATMULL_ROM,
) : EditorCommand {

    private var previousPosition: Keyframe<Vector3d>? = null
    private var previousRotation: Keyframe<Rotation>? = null
    private var previousFov: Keyframe<Double>? = null

    override val label: String get() = "Set keyframe"

    override fun apply(project: EditorProject) {
        previousPosition = project.camera.position.at(nanos)
        previousRotation = project.camera.rotation.at(nanos)
        previousFov = project.camera.fov.at(nanos)
        project.camera.keyframe(nanos, pose, easing, mode)
    }

    override fun revert(project: EditorProject) {
        project.camera.removeKeyframe(nanos)
        previousPosition?.let { project.camera.position.set(it) }
        previousRotation?.let { project.camera.rotation.set(it) }
        previousFov?.let { project.camera.fov.set(it) }
    }

    override fun mergeWith(next: EditorCommand): EditorCommand? {
        if (next !is SetCameraKeyframe || next.nanos != nanos) return null
        return SetCameraKeyframe(nanos, next.pose, next.easing, next.mode).also {
            it.previousPosition = previousPosition
            it.previousRotation = previousRotation
            it.previousFov = previousFov
        }
    }
}
