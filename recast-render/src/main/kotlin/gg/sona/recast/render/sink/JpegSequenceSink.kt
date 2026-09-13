package gg.sona.recast.render.sink

import gg.sona.recast.render.ExportSettings
import gg.sona.recast.render.RenderedFrame
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

class JpegSequenceSink(
    private val directory: Path,
    private val quality: Int = 92,
    private val prefix: String = "frame"
) : FrameSink {

    override fun begin(settings: ExportSettings) {
        Files.createDirectories(directory)
    }

    override fun accept(frame: RenderedFrame) {
        val image = BufferedImage(frame.width, frame.height, BufferedImage.TYPE_INT_RGB)
        val pixels = IntArray(frame.width * frame.height)
        val source = frame.rgba
        for (index in pixels.indices) {
            val offset = index * 4
            pixels[index] =
                ((source[offset].toInt() and 0xFF) shl 16) or ((source[offset + 1].toInt() and 0xFF) shl 8) or (source[offset + 2].toInt() and 0xFF)
        }
        image.setRGB(0, 0, frame.width, frame.height, pixels, 0, frame.width)
        if (!Files.isDirectory(directory)) Files.createDirectories(directory)
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        val parameters = writer.defaultWriteParam
        parameters.compressionMode = javax.imageio.ImageWriteParam.MODE_EXPLICIT
        parameters.compressionQuality = (quality.coerceIn(1, 100) / 100f)
        javax.imageio.stream.FileImageOutputStream(
            directory.resolve(String.format("%s-%06d.jpg", prefix, frame.index)).toFile()
        ).use { output ->
            writer.output = output
            writer.write(null, javax.imageio.IIOImage(image, null, null), parameters)
        }
        writer.dispose()
    }

    override fun close() {}
}
