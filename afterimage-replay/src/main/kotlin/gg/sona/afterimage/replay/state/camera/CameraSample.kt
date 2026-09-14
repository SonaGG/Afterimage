package gg.sona.afterimage.replay.state.camera

class CameraSample(
    val nanos: Long,
    val modelView: FloatArray,
    val fov: Float,
    val position: DoubleArray? = null,
    val hand: FloatArray? = null
)
