package gg.sona.recast.editor.commands

import gg.sona.recast.camera.CameraPathKey
import gg.sona.recast.camera.Rotation
import gg.sona.recast.camera.track.Keyframe
import gg.sona.recast.camera.track.Track
import gg.sona.recast.editor.EditorCommand
import gg.sona.recast.editor.EditorProject
import org.joml.Vector3d

class SetCameraHandles(
    private val nanos: Long,
    private val position: Handles<Vector3d>? = null,
    private val rotation: Handles<Rotation>? = null,
    private val fov: Handles<Double>? = null,
) : EditorCommand {

    class Handles<T>(val handleIn: T?, val handleOut: T?)

    private var previous: CameraPathKey? = null

    override val label: String get() = "Adjust handles"

    override fun apply(project: EditorProject) {
        previous = project.camera.snapshot(nanos) ?: return
        position?.let { applyTo(project.camera.position, it) }
        rotation?.let { applyTo(project.camera.rotation, it) }
        fov?.let { applyTo(project.camera.fov, it) }
    }

    private fun <T> applyTo(track: Track<T>, handles: Handles<T>) {
        track.update(nanos) { it.copy(handleIn = handles.handleIn, handleOut = handles.handleOut) }
    }

    override fun revert(project: EditorProject) {
        previous?.let { project.camera.restore(it) }
    }

    override fun mergeWith(next: EditorCommand): EditorCommand? {
        if (next !is SetCameraHandles || next.nanos != nanos) return null
        return SetCameraHandles(nanos, next.position ?: position, next.rotation ?: rotation, next.fov ?: fov).also {
            it.previous = previous
        }
    }

    companion object {
        fun <T> keep(keyframe: Keyframe<T>?): Handles<T> = Handles(keyframe?.handleIn, keyframe?.handleOut)
    }
}
