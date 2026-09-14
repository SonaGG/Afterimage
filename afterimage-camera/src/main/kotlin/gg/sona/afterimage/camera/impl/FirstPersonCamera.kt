package gg.sona.afterimage.camera.impl

import gg.sona.afterimage.camera.CameraBehavior
import gg.sona.afterimage.camera.CameraPose
import gg.sona.afterimage.camera.PoseSource

class FirstPersonCamera(private val target: PoseSource) : CameraBehavior {
    override fun poseAt(nanos: Long): CameraPose? = target.poseAt(nanos)
}