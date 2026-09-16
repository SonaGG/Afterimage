package gg.sona.afterimage.gfx

class SizedTexture(private val gfx: Gfx, private val format: TextureFormat, private val filter: Filter = Filter.NEAREST) : AutoCloseable {
    var texture: Texture? = null
        private set

    val width: Int get() = texture?.width ?: 0
    val height: Int get() = texture?.height ?: 0

    fun ensure(width: Int, height: Int): Texture {
        val current = texture
        if (current != null && current.width == width && current.height == height) return current
        current?.close()
        return gfx.createTexture(width, height, format, filter).also { texture = it }
    }

    override fun close() {
        texture?.close()
        texture = null
    }
}
