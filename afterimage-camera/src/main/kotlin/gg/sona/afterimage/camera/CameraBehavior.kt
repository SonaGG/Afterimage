package gg.sona.afterimage.camera

interface CameraBehavior {
    fun poseAt(nanos: Long): CameraPose?
    fun update(deltaNanos: Long) {}
}