package gg.sona.afterimage.editor.commands

import gg.sona.afterimage.camera.Rotation
import gg.sona.afterimage.camera.track.Keyframe
import gg.sona.afterimage.editor.EditorCommand
import gg.sona.afterimage.editor.EditorProject
import org.joml.Vector3d

class RemoveCameraKeyframe(private val nanos: Long) : EditorCommand {
    private var position: Keyframe<Vector3d>? = null
    private var rotation: Keyframe<Rotation>? = null
    private var fov: Keyframe<Double>? = null

    override val label: String get() = "Remove keyframe"

    override fun apply(project: EditorProject) {
        position = project.camera.position.at(nanos)
        rotation = project.camera.rotation.at(nanos)
        fov = project.camera.fov.at(nanos)
        project.camera.removeKeyframe(nanos)
    }

    override fun revert(project: EditorProject) {
        position?.let { project.camera.position.set(it) }
        rotation?.let { project.camera.rotation.set(it) }
        fov?.let { project.camera.fov.set(it) }
    }
}
