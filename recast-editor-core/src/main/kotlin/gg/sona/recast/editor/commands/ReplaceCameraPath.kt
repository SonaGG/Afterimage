package gg.sona.recast.editor.commands

import gg.sona.recast.camera.CameraPath
import gg.sona.recast.editor.EditorCommand
import gg.sona.recast.editor.EditorProject
import org.joml.Vector3d

class ReplaceCameraPath(private val replacement: CameraPath, override val label: String = "Load camera path") :
    EditorCommand {

    private var previous: CameraPath? = null

    override fun apply(project: EditorProject) {
        previous = project.camera.copy()
        copyInto(project.camera, replacement)
    }

    override fun revert(project: EditorProject) {
        previous?.let { copyInto(project.camera, it) }
    }

    companion object {
        fun copyInto(target: CameraPath, source: CameraPath) {
            target.keyframes().forEach { target.removeKeyframe(it.timeNanos) }
            source.position.keyframes.forEach {
                target.position.set(
                    it.copy(
                        value = Vector3d(it.value),
                        handleIn = it.handleIn?.let { v -> Vector3d(v) },
                        handleOut = it.handleOut?.let { v -> Vector3d(v) })
                )
            }
            source.rotation.keyframes.forEach { target.rotation.set(it) }
            source.fov.keyframes.forEach { target.fov.set(it) }
        }
    }
}
