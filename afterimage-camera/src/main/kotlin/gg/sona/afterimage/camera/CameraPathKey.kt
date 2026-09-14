package gg.sona.afterimage.camera

import gg.sona.afterimage.camera.track.Keyframe
import org.joml.Vector3d

data class CameraPathKey(
    val timeNanos: Long,
    val position: Keyframe<Vector3d>?,
    val rotation: Keyframe<Rotation>?,
    val fov: Keyframe<Double>?,
) {
    fun at(timeNanos: Long): CameraPathKey = CameraPathKey(
        timeNanos,
        position?.copy(timeNanos = timeNanos),
        rotation?.copy(timeNanos = timeNanos),
        fov?.copy(timeNanos = timeNanos),
    )

    fun deepCopy(): CameraPathKey = CameraPathKey(
        timeNanos,
        position?.let {
            it.copy(
                value = Vector3d(it.value),
                handleIn = it.handleIn?.let { v -> Vector3d(v) },
                handleOut = it.handleOut?.let { v -> Vector3d(v) })
        },
        rotation,
        fov,
    )
}
