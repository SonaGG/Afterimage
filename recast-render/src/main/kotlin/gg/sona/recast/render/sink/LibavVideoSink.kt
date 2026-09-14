package gg.sona.recast.render.sink

import gg.sona.recast.render.ExportEncoding
import gg.sona.recast.render.ExportFormat
import gg.sona.recast.render.ExportSettings
import gg.sona.recast.render.RenderedFrame
import gg.sona.recast.render.ffmpeg.FfmpegLog
import gg.sona.recast.render.ffmpeg.Libav
import gg.sona.recast.render.ffmpeg.MediaOutput
import gg.sona.recast.render.ffmpeg.VideoEncoder
import gg.sona.recast.render.ffmpeg.VideoFilterGraph
import org.bytedeco.ffmpeg.avformat.AVStream
import org.bytedeco.ffmpeg.avutil.AVFrame
import org.bytedeco.ffmpeg.global.avcodec
import org.bytedeco.ffmpeg.global.avutil
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

class LibavVideoSink(private val encoders: Set<String> = emptySet()) : FrameSink {

    private inner class Stream(val target: Path, val inputFormat: Int, val bytesPerPixel: Int) {
        val part: Path = partFile(target)
        var output: MediaOutput? = null
        var stream: AVStream? = null
        var filters: VideoFilterGraph? = null
        var encoder: VideoEncoder? = null
        var input: AVFrame? = null
        var filtered: AVFrame? = null

        fun open(
            settings: ExportSettings,
            codecName: String,
            requestedFormat: String,
            filterGraph: (String) -> String,
            tagSource: (AVFrame) -> Unit,
        ) {
            val codec = avcodec.avcodec_find_encoder_by_name(codecName)
                ?: throw IllegalStateException("encoder $codecName is not available")
            val pixelFormat = VideoEncoder.choosePixelFormat(codec, avutil.av_get_pix_fmt(requestedFormat))
            val pixelFormatName = avutil.av_get_pix_fmt_name(pixelFormat).string
            val color = ExportEncoding.videoColor(settings)
            Files.deleteIfExists(part)
            val output = MediaOutput(part, target).also { output = it }
            val filters = VideoFilterGraph(width, height, settings.fps, inputFormat, filterGraph(pixelFormatName))
                .also { filters = it }
            val encoder = VideoEncoder(
                codecName, width, height, settings.fps, filters.outputFormat,
                ExportEncoding.codecOptions(settings, codecName), output.globalHeader, color
            ).also { encoder = it }
            val stream = output.addStream().also { stream = it }
            Libav.check(avcodec.avcodec_parameters_from_context(stream.codecpar(), encoder.context), "stream parameters")
            ExportEncoding.videoTag(settings)?.let { stream.codecpar().codec_tag(tag(it)) }
            stream.time_base(encoder.context.time_base())
            output.writeHeader(ExportEncoding.containerOptions(settings))
            input = avutil.av_frame_alloc().also {
                it.format(inputFormat)
                it.width(width)
                it.height(height)
                tagSource(it)
                Libav.check(avutil.av_frame_get_buffer(it, 0), "frame buffer")
            }
            filtered = avutil.av_frame_alloc()
        }

        fun push(index: Long, fill: (ByteBuffer, Int, Int) -> Unit) {
            val input = input ?: error("sink not started")
            Libav.check(avutil.av_frame_make_writable(input), "frame buffer")
            val stride = input.linesize(0)
            val rowBytes = width * bytesPerPixel
            val buffer = input.data(0).position(0L).capacity(stride.toLong() * height).asByteBuffer()
            fill(buffer, stride, rowBytes)
            input.pts(index)
            filters!!.push(input)
            drain()
        }

        private fun drain() {
            val filters = filters!!
            val encoder = encoder!!
            val filtered = filtered!!
            while (filters.pull(filtered)) {
                try {
                    encoder.encode(filtered) { packet -> output!!.write(packet, encoder.context.time_base(), stream!!) }
                } finally {
                    avutil.av_frame_unref(filtered)
                }
            }
        }

        fun finish() {
            try {
                val filters = filters ?: return
                filters.push(null)
                drain()
                val encoder = encoder!!
                encoder.encode(null) { packet -> output!!.write(packet, encoder.context.time_base(), stream!!) }
                output!!.finish()
            } finally {
                release()
            }
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING)
        }

        fun abort() {
            try {
                release()
            } finally {
                Files.deleteIfExists(part)
            }
        }

        fun release() {
            input?.let { avutil.av_frame_free(it) }
            filtered?.let { avutil.av_frame_free(it) }
            input = null
            filtered = null
            encoder?.close()
            encoder = null
            filters?.close()
            filters = null
            output?.close()
            output = null
            stream = null
        }
    }

    private var color: Stream? = null
    private var depth: Stream? = null
    private var width = 0
    private var height = 0
    private var depthRow: ByteArray = ByteArray(0)

    override fun begin(settings: ExportSettings) {
        FfmpegLog.clear()
        width = settings.width
        height = settings.height
        val codecName = ExportEncoding.resolveCodec(settings, encoders)
        val videoColor = ExportEncoding.videoColor(settings)
        try {
            color = Stream(settings.output, avutil.AV_PIX_FMT_RGBA, 4).also { stream ->
                stream.open(
                    settings,
                    codecName,
                    ExportEncoding.requestedPixelFormat(settings),
                    { format -> ExportEncoding.videoFilters(settings, format.takeIf { settings.format != ExportFormat.GIF }) },
                    { frame -> videoColor?.tagRgbSource(frame) },
                )
            }
            if (settings.depthMap && ExportEncoding.supportsDepth(settings.format)) {
                depthRow = ByteArray(width * 2)
                depth = Stream(ExportEncoding.depthOutput(settings.output), avutil.AV_PIX_FMT_GRAY16LE, 2).also { stream ->
                    stream.open(
                        settings,
                        codecName,
                        ExportEncoding.depthPixelFormat(settings, codecName),
                        { format -> ExportEncoding.depthVideoFilters(settings, format) },
                        { frame -> videoColor?.tagGraySource(frame) },
                    )
                }
            }
        } catch (error: Throwable) {
            release()
            throw error
        }
    }

    override fun accept(frame: RenderedFrame) {
        val color = color ?: error("sink not started")
        color.push(frame.index) { buffer, stride, rowBytes ->
            if (stride == rowBytes) {
                buffer.position(0).put(frame.rgba, 0, rowBytes * height)
            } else {
                for (y in 0 until height) buffer.position(y * stride).put(frame.rgba, y * rowBytes, rowBytes)
            }
        }
        val depth = depth ?: return
        val values = frame.depth ?: return
        depth.push(frame.index) { buffer, stride, rowBytes ->
            val row = depthRow
            for (y in 0 until height) {
                val base = y * width
                for (x in 0 until width) {
                    val value = (values[base + x].coerceIn(0f, 1f) * 65535f + 0.5f).toInt()
                    row[x * 2] = (value and 0xFF).toByte()
                    row[x * 2 + 1] = ((value shr 8) and 0xFF).toByte()
                }
                buffer.position(y * stride).put(row, 0, rowBytes)
            }
        }
    }

    override fun close() {
        try {
            color?.finish()
            depth?.finish()
        } finally {
            release()
        }
    }

    override fun abort() {
        color?.abort()
        depth?.abort()
        color = null
        depth = null
    }

    private fun release() {
        color?.release()
        depth?.release()
    }

    private fun tag(text: String): Int =
        text[0].code or (text[1].code shl 8) or (text[2].code shl 16) or (text[3].code shl 24)

    companion object {
        fun partFile(output: Path): Path = output.resolveSibling(output.fileName.toString() + ".part")
    }
}
