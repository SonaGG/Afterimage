package gg.sona.recast.camera.impl

import gg.sona.recast.camera.CameraBehavior
import gg.sona.recast.camera.CameraPose
import gg.sona.recast.camera.PoseSource
import gg.sona.recast.camera.Rotation
import gg.sona.recast.core.time.Nanos
import org.joml.Vector3d

class ChaseCamera(private val target: PoseSource) : CameraBehavior {
    var distance: Double = 6.0
    var height: Double = 2.0
    var stiffness: Double = 30.0
    var damping: Double = 8.0
    var fov: Double = CameraPose.DEFAULT_FOV

    private val position = Vector3d()
    private val velocity = Vector3d()
    private var initialized = false
    private var latestAnchor: CameraPose? = null

    override fun update(deltaNanos: Long) {
        val anchor = latestAnchor ?: return
        val dt = deltaNanos / Nanos.PER_SECOND.toDouble()
        if (dt <= 0.0) return
        val desired = Vector3d(anchor.position).add(Rotation(anchor.rotation.yaw, 0.0).forward().mul(-distance))
            .add(0.0, height, 0.0)
        if (!initialized) {
            position.set(desired)
            initialized = true
            return
        }
        val displacement = Vector3d(desired).sub(position)
        val acceleration = displacement.mul(stiffness).sub(Vector3d(velocity).mul(damping))
        velocity.add(acceleration.mul(dt))
        position.add(Vector3d(velocity).mul(dt))
    }

    override fun poseAt(nanos: Long): CameraPose? {
        val anchor = target.poseAt(nanos) ?: return null
        latestAnchor = anchor
        if (!initialized) update(1L)
        return CameraPose(Vector3d(position), Rotation.lookingAt(position, anchor.position), fov)
    }
}
