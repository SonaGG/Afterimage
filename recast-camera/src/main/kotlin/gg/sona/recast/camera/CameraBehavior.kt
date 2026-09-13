package gg.sona.recast.camera

interface CameraBehavior {
    fun poseAt(nanos: Long): CameraPose?
    fun update(deltaNanos: Long) {}
}