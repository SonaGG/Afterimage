package gg.sona.recast.camera.impl

import gg.sona.recast.camera.CameraBehavior
import gg.sona.recast.camera.CameraPose
import gg.sona.recast.camera.PoseSource

class FirstPersonCamera(private val target: PoseSource) : CameraBehavior {
    override fun poseAt(nanos: Long): CameraPose? = target.poseAt(nanos)
}