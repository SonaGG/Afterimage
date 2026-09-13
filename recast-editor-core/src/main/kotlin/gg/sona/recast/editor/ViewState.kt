package gg.sona.recast.editor

import gg.sona.recast.camera.CameraMode
import gg.sona.recast.camera.CameraSettings
import gg.sona.recast.camera.TrackingBodyPart

data class ViewState(
    val mode: CameraMode,
    val targetEntityId: Int,
    val orbitDistance: Double,
    val orbitPitch: Double,
    val orbitYawOffset: Double,
    val orbitHeight: Double,
    val followOffsetX: Double,
    val followOffsetY: Double,
    val followOffsetZ: Double,
    val chaseDistance: Double,
    val chaseHeight: Double,
    val orbitDegreesPerSecond: Double = 0.0,
    val followLookAtTarget: Boolean = true,
    val chaseStiffness: Double = 30.0,
    val chaseDamping: Double = 8.0,
    val bodyPart: TrackingBodyPart = TrackingBodyPart.HEAD,
    val targetOffsetX: Double = 0.0,
    val targetOffsetY: Double = 0.0,
    val targetOffsetZ: Double = 0.0,
) {
    fun applyTo(settings: CameraSettings) {
        settings.mode = mode
        settings.targetEntityId = targetEntityId
        settings.orbitDistance = orbitDistance
        settings.orbitPitch = orbitPitch
        settings.orbitYawOffset = orbitYawOffset
        settings.orbitHeight = orbitHeight
        settings.orbitDegreesPerSecond = orbitDegreesPerSecond
        settings.followOffsetX = followOffsetX
        settings.followOffsetY = followOffsetY
        settings.followOffsetZ = followOffsetZ
        settings.followLookAtTarget = followLookAtTarget
        settings.chaseDistance = chaseDistance
        settings.chaseHeight = chaseHeight
        settings.chaseStiffness = chaseStiffness
        settings.chaseDamping = chaseDamping
        settings.targetBodyPart = bodyPart
        settings.targetOffsetX = targetOffsetX
        settings.targetOffsetY = targetOffsetY
        settings.targetOffsetZ = targetOffsetZ
    }

    companion object {
        fun capture(settings: CameraSettings): ViewState = ViewState(
            settings.mode,
            settings.targetEntityId,
            settings.orbitDistance,
            settings.orbitPitch,
            settings.orbitYawOffset,
            settings.orbitHeight,
            settings.followOffsetX,
            settings.followOffsetY,
            settings.followOffsetZ,
            settings.chaseDistance,
            settings.chaseHeight,
            settings.orbitDegreesPerSecond,
            settings.followLookAtTarget,
            settings.chaseStiffness,
            settings.chaseDamping,
            settings.targetBodyPart,
            settings.targetOffsetX,
            settings.targetOffsetY,
            settings.targetOffsetZ,
        )
    }
}
