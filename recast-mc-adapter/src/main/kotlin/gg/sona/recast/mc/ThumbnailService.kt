package gg.sona.recast.mc

import net.minecraft.client.Minecraft
import net.minecraft.client.render.platform.GlStateManager
import org.apache.logging.log4j.LogManager
import org.lwjgl.BufferUtils
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL12
import java.awt.image.BufferedImage
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO

class ThumbnailService(private val minecraft: Minecraft, private val workspaceViewport: () -> IntArray?) {

    private val logger = LogManager.getLogger("Recast")
    private val textures = ConcurrentHashMap<Path, Int>()
    private val missing = ConcurrentHashMap.newKeySet<Path>()
    private var pendingTarget: Path? = null
    private var pendingFrames = 0
    private var readback: ByteBuffer? = null

    fun request(recording: Path, delayFrames: Int = 0) {
        pendingTarget = recording
        pendingFrames = delayFrames
    }

    fun thumbnailPath(recording: Path): Path =
        recording.resolveSibling(recording.fileName.toString().substringBeforeLast('.') + ".thumb.png")

    fun onWorldRendered() {
        val target = pendingTarget ?: return
        if (pendingFrames > 0) {
            pendingFrames--
            return
        }
        pendingTarget = null
        if (minecraft.world == null) return
        runCatching { capture(target) }.onFailure { logger.warn("Recast could not capture a thumbnail", it) }
    }

    private fun capture(recording: Path) {
        val viewport = workspaceViewport() ?: intArrayOf(0, 0, minecraft.width, minecraft.height)
        val x = viewport[0]
        val y = viewport[1]
        val width = viewport[2]
        val height = viewport[3]
        if (width <= 0 || height <= 0) return
        val needed = width * height * 4
        var buffer = readback
        if (buffer == null || buffer.capacity() < needed) {
            buffer = BufferUtils.createByteBuffer(needed)
            readback = buffer
        }
        buffer.clear()
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1)
        GL11.glReadPixels(x, y, width, height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buffer)
        val pixels = ByteArray(needed)
        buffer.get(pixels)
        val output = thumbnailPath(recording)
        Thread({
            runCatching {
                val scaleWidth = THUMB_WIDTH
                val scaleHeight = maxOf(1, THUMB_WIDTH * height / width)
                val image = BufferedImage(scaleWidth, scaleHeight, BufferedImage.TYPE_INT_RGB)
                for (ty in 0 until scaleHeight) {
                    val sy = height - 1 - (ty * height / scaleHeight)
                    for (tx in 0 until scaleWidth) {
                        val sx = tx * width / scaleWidth
                        val offset = (sy * width + sx) * 4
                        val rgb =
                            ((pixels[offset].toInt() and 0xFF) shl 16) or ((pixels[offset + 1].toInt() and 0xFF) shl 8) or (pixels[offset + 2].toInt() and 0xFF)
                        image.setRGB(tx, ty, rgb)
                    }
                }
                Files.createDirectories(output.parent)
                ImageIO.write(image, "png", output.toFile())
                invalidate(recording)
            }.onFailure { logger.warn("Recast could not write thumbnail {}", output, it) }
        }, "recast-thumbnail").apply { isDaemon = true }.start()
    }

    fun invalidate(recording: Path) {
        missing.remove(recording)
        val texture = textures.remove(recording) ?: return
        minecraft.executeTask(Runnable { GL11.glDeleteTextures(texture) })
    }

    fun texture(recording: Path): Int? {
        textures[recording]?.let { return it }
        if (recording in missing) return null
        val file = thumbnailPath(recording)
        if (!Files.exists(file)) {
            missing.add(recording)
            return null
        }
        val image = runCatching { ImageIO.read(file.toFile()) }.getOrNull()
        if (image == null) {
            missing.add(recording)
            return null
        }
        val width = image.width
        val height = image.height
        val data = BufferUtils.createByteBuffer(width * height * 4)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val rgb = image.getRGB(x, y)
                data.put(((rgb shr 16) and 0xFF).toByte()).put(((rgb shr 8) and 0xFF).toByte())
                    .put((rgb and 0xFF).toByte()).put(0xFF.toByte())
            }
        }
        data.flip()
        val id = GL11.glGenTextures()
        GlStateManager.bindTexture(id)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE)
        GL11.glTexImage2D(
            GL11.GL_TEXTURE_2D,
            0,
            GL11.GL_RGBA,
            width,
            height,
            0,
            GL11.GL_RGBA,
            GL11.GL_UNSIGNED_BYTE,
            data
        )
        GlStateManager.bindTexture(0)
        textures[recording] = id
        return id
    }

    fun shutdown() {
        for (texture in textures.values) runCatching { GL11.glDeleteTextures(texture) }
        textures.clear()
    }

    private companion object {
        const val THUMB_WIDTH = 320
    }
}
