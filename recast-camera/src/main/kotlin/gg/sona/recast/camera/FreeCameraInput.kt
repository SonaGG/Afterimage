package gg.sona.recast.camera

data class FreeCameraInput(
    val forward: Double = 0.0,
    val strafe: Double = 0.0,
    val vertical: Double = 0.0,
    val yawDelta: Double = 0.0,
    val pitchDelta: Double = 0.0,
    val rollDelta: Double = 0.0,
    val sprint: Boolean = false,
) {
    companion object {
        val NONE = FreeCameraInput()
    }
}