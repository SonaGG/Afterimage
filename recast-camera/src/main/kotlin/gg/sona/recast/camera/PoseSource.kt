package gg.sona.recast.camera

fun interface PoseSource {
    fun poseAt(nanos: Long): CameraPose?
}