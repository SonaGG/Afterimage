package gg.sona.afterimage.camera

fun interface PoseSource {
    fun poseAt(nanos: Long): CameraPose?
}