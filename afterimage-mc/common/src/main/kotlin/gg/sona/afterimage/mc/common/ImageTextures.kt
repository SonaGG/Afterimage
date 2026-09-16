package gg.sona.afterimage.mc.common

import gg.sona.afterimage.gfx.Filter
import gg.sona.afterimage.gfx.Texture
import gg.sona.afterimage.core.log.AfterimageLog
import gg.sona.afterimage.gfx.Gfx
import org.lwjgl.BufferUtils
import java.nio.ByteBuffer
import javax.imageio.ImageIO

class ImageTextures(private val gfx: Gfx) {
    private val logger = AfterimageLog.logger("Afterimage")
    private val textures = HashMap<String, Texture?>()

    fun texture(resource: String): IntArray? = textures.getOrPut(resource) { load(resource) }?.let { intArrayOf(it.handle, it.width, it.height) }

    private fun load(resource: String): Texture? {
        val image = runCatching {
            ImageTextures::class.java.getResourceAsStream(resource)?.use { ImageIO.read(it) }
        }.getOrNull()
        if (image == null) {
            logger.warn("Afterimage could not load image $resource")
            return null
        }
        var width = image.width
        var height = image.height
        var pixels = image.getRGB(0, 0, width, height, null, 0, width)
        val levels = ArrayList<ByteBuffer>()
        while (true) {
            levels += rgba(pixels)
            if (width == 1 && height == 1) break
            val nextWidth = maxOf(1, width / 2)
            val nextHeight = maxOf(1, height / 2)
            pixels = halve(pixels, width, height, nextWidth, nextHeight)
            width = nextWidth
            height = nextHeight
        }
        return gfx.uploadTextureLevels(image.width, image.height, levels, Filter.LINEAR_MIPMAP)
    }

    private fun rgba(pixels: IntArray): ByteBuffer {
        val buffer = BufferUtils.createByteBuffer(pixels.size * 4)
        for (argb in pixels) {
            buffer.put((argb shr 16).toByte()).put((argb shr 8).toByte()).put(argb.toByte()).put((argb ushr 24).toByte())
        }
        buffer.flip()
        return buffer
    }

    private fun halve(source: IntArray, width: Int, height: Int, nextWidth: Int, nextHeight: Int): IntArray {
        val result = IntArray(nextWidth * nextHeight)
        for (y in 0 until nextHeight) {
            val y0 = minOf(height - 1, y * 2)
            val y1 = minOf(height - 1, y * 2 + 1)
            for (x in 0 until nextWidth) {
                val x0 = minOf(width - 1, x * 2)
                val x1 = minOf(width - 1, x * 2 + 1)
                var alpha = 0
                var red = 0
                var green = 0
                var blue = 0
                for (argb in intArrayOf(source[y0 * width + x0], source[y0 * width + x1], source[y1 * width + x0], source[y1 * width + x1])) {
                    val a = argb ushr 24
                    alpha += a
                    red += ((argb shr 16) and 0xFF) * a
                    green += ((argb shr 8) and 0xFF) * a
                    blue += (argb and 0xFF) * a
                }
                result[y * nextWidth + x] = if (alpha == 0) 0 else
                    ((alpha / 4) shl 24) or ((red / alpha) shl 16) or ((green / alpha) shl 8) or (blue / alpha)
            }
        }
        return result
    }

    fun shutdown() {
        for (texture in textures.values) texture?.let { runCatching { it.close() } }
        textures.clear()
    }
}
