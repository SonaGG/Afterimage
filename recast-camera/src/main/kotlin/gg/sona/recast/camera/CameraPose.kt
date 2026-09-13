package gg.sona.recast.camera

import org.joml.Vector3d

data class CameraPose(val position: Vector3d, val rotation: Rotation, val fov: Double = DEFAULT_FOV) {
    fun lerp(other: CameraPose, t: Double): CameraPose = CameraPose(
        Vector3d(position).lerp(other.position, t),
        Rotation(
            Rotation.lerpAngle(rotation.yaw, other.rotation.yaw, t),
            rotation.pitch + (other.rotation.pitch - rotation.pitch) * t,
            Rotation.lerpAngle(rotation.roll, other.rotation.roll, t),
        ),
        fov + (other.fov - fov) * t,
    )

    companion object {
        const val DEFAULT_FOV = 70.0
        val ORIGIN = CameraPose(Vector3d(), Rotation.ZERO)
    }
}
