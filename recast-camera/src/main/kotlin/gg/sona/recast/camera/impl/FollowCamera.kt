package gg.sona.recast.camera.impl

import gg.sona.recast.camera.CameraBehavior
import gg.sona.recast.camera.CameraPose
import gg.sona.recast.camera.PoseSource
import gg.sona.recast.camera.Rotation
import org.joml.Vector3d

class FollowCamera(private val target: PoseSource) : CameraBehavior {
    var offset: Vector3d = Vector3d(0.0, 2.0, -4.0)
    var lookAtTarget: Boolean = true
    var fov: Double = CameraPose.DEFAULT_FOV

    private var current: Vector3d? = null

    override fun poseAt(nanos: Long): CameraPose? {
        val anchor = target.poseAt(nanos) ?: return null
        val yawRotation = Rotation(anchor.rotation.yaw, 0.0)
        val desired = Vector3d(anchor.position)
            .add(Vector3d(yawRotation.right()).mul(offset.x))
            .add(0.0, offset.y, 0.0)
            .add(Vector3d(yawRotation.forward()).mul(offset.z))
        val position = current?.also { it.lerp(desired, 0.5) } ?: Vector3d(desired).also { current = it }
        val rotation = if (lookAtTarget) Rotation.lookingAt(position, anchor.position) else anchor.rotation
        return CameraPose(Vector3d(position), rotation, fov)
    }

    override fun update(deltaNanos: Long) {}
}