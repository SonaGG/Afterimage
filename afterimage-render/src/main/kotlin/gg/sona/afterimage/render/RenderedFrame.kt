package gg.sona.afterimage.render


class RenderedFrame(
    val index: Long,
    val nanos: Long,
    val width: Int,
    val height: Int,
    val rgba: ByteArray,
    val depth: FloatArray? = null
)
