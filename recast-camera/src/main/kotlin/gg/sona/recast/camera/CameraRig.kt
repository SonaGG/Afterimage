package gg.sona.recast.camera

import gg.sona.recast.camera.shake.CameraShake
import gg.sona.recast.camera.shake.ShakeOffset
import org.joml.Vector3d

class CameraRig(var behavior: CameraBehavior, val shake: CameraShake = CameraShake()) {
    fun update(deltaNanos: Long) = behavior.update(deltaNanos)

    fun poseAt(nanos: Long): CameraPose? {
        val base = behavior.poseAt(nanos) ?: return null
        val offset = shake.offsetAt(nanos)
        if (offset === ShakeOffset.NONE) return base
        return CameraPose(
            Vector3d(base.position).add(offset.position),
            Rotation(
                base.rotation.yaw + offset.rotation.yaw,
                base.rotation.pitch + offset.rotation.pitch,
                base.rotation.roll + offset.rotation.roll
            ),
            base.fov,
        )
    }
}
