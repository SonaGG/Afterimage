package gg.sona.afterimage.editor.host

import gg.sona.afterimage.camera.CameraPose
import gg.sona.afterimage.camera.CameraSettings

interface CameraControl {
    val settings: CameraSettings

    fun apply()

    fun currentPose(): CameraPose

    fun teleport(pose: CameraPose)

    fun detachedFromPlayer(): Boolean

    val pathActive: Boolean

    val pathSuspended: Boolean

    fun frame(x: Double, y: Double, z: Double, radius: Double)

    fun snapView(yaw: Double, pitch: Double)

    val gestureActive: Boolean

    fun speedFlashUntilNanos(): Long

    fun recentGestureTravel(): Double

    fun bakePoses(fromNanos: Long, toNanos: Long, stepNanos: Long): List<CameraPose>
}
