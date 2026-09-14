package gg.sona.afterimage.camera.impl

import gg.sona.afterimage.camera.CameraBehavior
import gg.sona.afterimage.camera.CameraPose
import gg.sona.afterimage.camera.FreeCameraInput
import gg.sona.afterimage.camera.Rotation
import gg.sona.afterimage.core.time.Nanos
import org.joml.Vector3d
import kotlin.math.exp

class FreeCamera(initial: CameraPose = CameraPose.ORIGIN) : CameraBehavior {
    var pose: CameraPose = initial
    var speedBlocksPerSecond: Double = 8.0
    var sprintMultiplier: Double = 3.0
    var smoothing: Double = 12.0
    var input: FreeCameraInput = FreeCameraInput.NONE

    private val velocity = Vector3d()

    override fun poseAt(nanos: Long): CameraPose = pose

    override fun update(deltaNanos: Long) {
        val dt = deltaNanos / Nanos.PER_SECOND.toDouble()
        if (dt <= 0.0) return
        val rotation = Rotation(
            pose.rotation.yaw + input.yawDelta,
            (pose.rotation.pitch + input.pitchDelta).coerceIn(-90.0, 90.0),
            pose.rotation.roll + input.rollDelta,
        )
        val forward = Rotation(rotation.yaw, 0.0).forward()
        val right = rotation.right()
        val speed = speedBlocksPerSecond * if (input.sprint) sprintMultiplier else 1.0
        val target =
            Vector3d(forward).mul(input.forward).add(Vector3d(right).mul(input.strafe)).add(0.0, input.vertical, 0.0)
        if (target.lengthSquared() > 1.0) target.normalize()
        target.mul(speed)
        val blend = (1.0 - exp(-smoothing * dt)).coerceIn(0.0, 1.0)
        velocity.lerp(target, blend)
        val position = Vector3d(pose.position).add(Vector3d(velocity).mul(dt))
        pose = CameraPose(position, rotation, pose.fov)
    }
}
