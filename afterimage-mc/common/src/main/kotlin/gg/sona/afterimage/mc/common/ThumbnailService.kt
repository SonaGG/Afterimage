package gg.sona.afterimage.mc.common

import gg.sona.afterimage.core.log.AfterimageLog
import gg.sona.afterimage.gfx.Gfx
import org.lwjgl.BufferUtils
import gg.sona.afterimage.gfx.Filter
import gg.sona.afterimage.gfx.Texture
import java.awt.image.BufferedImage
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO

class ThumbnailService(private val gfx: Gfx, private val host: ThumbnailHost, private val workspaceViewport: () -> IntArray?) {
    private val logger = AfterimageLog.logger("Afterimage")
    private val textures = ConcurrentHashMap<Path, Texture>()
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
        if (!host.worldLoaded()) return
        runCatching { capture(target) }.onFailure { logger.warn("Afterimage could not capture a thumbnail", it) }
    }

    private fun capture(recording: Path) {
        val viewport = workspaceViewport() ?: intArrayOf(0, 0, host.windowWidth(), host.windowHeight())
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
        gfx.readPixels(host.mainTarget(), x, y, width, height, buffer)
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
            }.onFailure { logger.warn("Afterimage could not write thumbnail $output", it) }
        }, "afterimage-thumbnail").apply { isDaemon = true }.start()
    }

    fun invalidate(recording: Path) {
        missing.remove(recording)
        val texture = textures.remove(recording) ?: return
        host.runOnGameThread { texture.close() }
    }

    fun texture(recording: Path): Int? {
        textures[recording]?.let { return it.handle }
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
        val texture = gfx.uploadTexture(width, height, data, Filter.LINEAR)
        textures[recording] = texture
        return texture.handle
    }

    fun shutdown() {
        for (texture in textures.values) runCatching { texture.close() }
        textures.clear()
    }

    private companion object {
        const val THUMB_WIDTH = 320
    }
}
