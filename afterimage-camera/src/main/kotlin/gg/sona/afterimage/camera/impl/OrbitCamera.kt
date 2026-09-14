package gg.sona.afterimage.camera.impl

import gg.sona.afterimage.camera.CameraBehavior
import gg.sona.afterimage.camera.CameraPose
import gg.sona.afterimage.camera.PoseSource
import gg.sona.afterimage.camera.Rotation
import gg.sona.afterimage.core.time.Nanos
import org.joml.Vector3d

class OrbitCamera(private val target: PoseSource) : CameraBehavior {
    var distance: Double = 5.0
    var yawOffset: Double = 0.0
    var pitch: Double = 20.0
    var heightOffset: Double = 0.0
    var degreesPerSecond: Double = 0.0
    var fov: Double = CameraPose.DEFAULT_FOV

    private var spin = 0.0

    override fun update(deltaNanos: Long) {
        spin += degreesPerSecond * deltaNanos / Nanos.PER_SECOND.toDouble()
    }

    override fun poseAt(nanos: Long): CameraPose? {
        val anchor = target.poseAt(nanos) ?: return null
        val yaw = anchor.rotation.yaw + yawOffset + spin
        val back = Rotation(yaw, pitch).forward().mul(-distance)
        val position = Vector3d(anchor.position).add(back).add(0.0, heightOffset, 0.0)
        return CameraPose(position, Rotation.lookingAt(position, anchor.position), fov)
    }
}