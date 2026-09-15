package gg.sona.afterimage.world.camera

class CameraSample(
    val nanos: Long,
    val modelView: FloatArray,
    val fov: Float,
    val position: DoubleArray? = null,
    val hand: FloatArray? = null
)
