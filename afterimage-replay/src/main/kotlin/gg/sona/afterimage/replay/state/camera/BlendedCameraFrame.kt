package gg.sona.afterimage.replay.state.camera

class BlendedCameraFrame(
    val modelView: FloatArray,
    val fov: Float,
    val position: DoubleArray?,
    val hand: FloatArray?,
    val exact: Boolean
)
