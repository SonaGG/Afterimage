package gg.sona.recast.camera

class CameraSettings {
    var mode: CameraMode = CameraMode.FREE
    var targetEntityId: Int = TARGET_RECORDER
    var interpolation: PoseInterpolation = PoseInterpolation.LINEAR
    var smoothing: Double = 0.0
    var fov: Double = CameraPose.DEFAULT_FOV
    var overrideFov: Boolean = false
    var roll: Double = 0.0
    var eyeHeight: Double = DEFAULT_EYE_HEIGHT
    var hideTargetInFirstPerson: Boolean = true
    var exactFirstPerson: Boolean = true
    var smoothEntities: Boolean = true
    var entityDelayMillis: Long = 150L
    var smoothEntityRotation: Boolean = false
    var showHud: Boolean = true
    var showHand: Boolean = true
    var showBlockOutline: Boolean = true
    var mirrorScreens: Boolean = true
    var mirrorCursor: Boolean = true
    var showPlayerList: Boolean = false
    var freeSpeed: Double = 8.0
    var freeSensitivity: Double = 0.15
    var freeAcceleration: Boolean = true
    var freeEasing: Boolean = true
    var lockX: Boolean = false
    var lockY: Boolean = false
    var lockZ: Boolean = false
    var lockYaw: Boolean = false
    var lockPitch: Boolean = false
    var orbitDistance: Double = 5.0
    var orbitPitch: Double = 20.0
    var orbitYawOffset: Double = 0.0
    var orbitDegreesPerSecond: Double = 0.0
    var orbitHeight: Double = 0.0
    var followOffsetX: Double = 0.0
    var followOffsetY: Double = 2.0
    var followOffsetZ: Double = -4.0
    var followLookAtTarget: Boolean = true
    var chaseDistance: Double = 6.0
    var chaseHeight: Double = 2.0
    var chaseStiffness: Double = 30.0
    var chaseDamping: Double = 8.0
    var targetBodyPart: TrackingBodyPart = TrackingBodyPart.HEAD
    var targetOffsetX: Double = 0.0
    var targetOffsetY: Double = 0.0
    var targetOffsetZ: Double = 0.0
    var showPath: Boolean = true
    var pathToleranceBlocks: Double = 0.05
    var shakeStrength: Double = 1.0
    var shakeFrequencyHz: Double = 1.6
    var shakeOnEvents: Boolean = false

    fun targetsRecorder(): Boolean = targetEntityId == TARGET_RECORDER

    companion object {
        const val TARGET_RECORDER = -1
        const val DEFAULT_EYE_HEIGHT = 1.62
    }
}
