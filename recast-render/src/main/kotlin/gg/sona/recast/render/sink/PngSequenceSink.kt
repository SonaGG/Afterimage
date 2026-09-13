package gg.sona.recast.render.sink

import gg.sona.recast.render.ExportSettings
import gg.sona.recast.render.RenderedFrame
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

class PngSequenceSink(private val directory: Path, private val prefix: String = "frame") : FrameSink {

    private var settings: ExportSettings? = null

    override fun begin(settings: ExportSettings) {
        this.settings = settings
        Files.createDirectories(directory)
    }

    override fun accept(frame: RenderedFrame) {
        val image = BufferedImage(frame.width, frame.height, BufferedImage.TYPE_INT_ARGB)
        val pixels = IntArray(frame.width * frame.height)
        val source = frame.rgba
        for (index in pixels.indices) {
            val offset = index * 4
            pixels[index] = ((source[offset + 3].toInt() and 0xFF) shl 24) or
                    ((source[offset].toInt() and 0xFF) shl 16) or
                    ((source[offset + 1].toInt() and 0xFF) shl 8) or
                    (source[offset + 2].toInt() and 0xFF)
        }
        image.setRGB(0, 0, frame.width, frame.height, pixels, 0, frame.width)
        if (!Files.isDirectory(directory)) Files.createDirectories(directory)
        writePng(image, directory.resolve(String.format("%s-%06d.png", prefix, frame.index)))
        val depth = frame.depth ?: return
        val depthImage = BufferedImage(frame.width, frame.height, BufferedImage.TYPE_USHORT_GRAY)
        val raster = depthImage.raster
        val row = IntArray(frame.width)
        for (y in 0 until frame.height) {
            for (x in 0 until frame.width) row[x] = (depth[y * frame.width + x].coerceIn(0f, 1f) * 65535f).toInt()
            raster.setSamples(0, y, frame.width, 1, 0, row)
        }
        ImageIO.write(
            depthImage,
            "png",
            directory.resolve(String.format("%s-%06d-depth.png", prefix, frame.index)).toFile()
        )
    }

    private fun writePng(image: BufferedImage, path: Path) {
        val writer = ImageIO.getImageWritersByFormatName("png").next()
        val parameters = writer.defaultWriteParam
        if (parameters.canWriteCompressed()) {
            parameters.compressionMode = javax.imageio.ImageWriteParam.MODE_EXPLICIT
            parameters.compressionQuality = 0.75f
        }
        javax.imageio.stream.FileImageOutputStream(path.toFile()).use { output ->
            writer.output = output
            writer.write(null, javax.imageio.IIOImage(image, null, null), parameters)
        }
        writer.dispose()
    }

    override fun close() {}
}
