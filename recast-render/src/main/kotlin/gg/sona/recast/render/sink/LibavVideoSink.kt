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
import org.bytedeco.ffmpeg.avcodec.AVPacket
import org.bytedeco.ffmpeg.avformat.AVStream
import org.bytedeco.ffmpeg.avutil.AVFrame
import org.bytedeco.ffmpeg.global.avcodec
import org.bytedeco.ffmpeg.global.avutil

class LibavVideoSink(private val encoders: Set<String> = emptySet()) : FrameSink {
    private var output: MediaOutput? = null
    private var stream: AVStream? = null
    private var filters: VideoFilterGraph? = null
    private var encoder: VideoEncoder? = null
    private var input: AVFrame? = null
    private var filtered: AVFrame? = null
    private var width = 0
    private var height = 0

    override fun begin(settings: ExportSettings) {
        FfmpegLog.clear()
        width = settings.width
        height = settings.height
        val codecName = ExportEncoding.resolveCodec(settings, encoders)
        val codec = avcodec.avcodec_find_encoder_by_name(codecName)
            ?: throw IllegalStateException("encoder $codecName is not available")
        val requested = avutil.av_get_pix_fmt(ExportEncoding.requestedPixelFormat(settings))
        val pixelFormat = VideoEncoder.choosePixelFormat(codec, requested)
        val pixelFormatName = avutil.av_get_pix_fmt_name(pixelFormat).string
        val color = ExportEncoding.videoColor(settings)
        try {
            val output = MediaOutput(settings.output).also { output = it }
            val filters = VideoFilterGraph(
                width, height, settings.fps, avutil.AV_PIX_FMT_RGBA,
                ExportEncoding.videoFilters(settings, pixelFormatName.takeIf { settings.format != ExportFormat.GIF })
            ).also { filters = it }
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
                it.format(avutil.AV_PIX_FMT_RGBA)
                it.width(width)
                it.height(height)
                color?.tagRgbSource(it)
                Libav.check(avutil.av_frame_get_buffer(it, 0), "frame buffer")
            }
            filtered = avutil.av_frame_alloc()
        } catch (error: Throwable) {
            release()
            throw error
        }
    }

    override fun accept(frame: RenderedFrame) {
        val input = input ?: error("sink not started")
        Libav.check(avutil.av_frame_make_writable(input), "frame buffer")
        val stride = input.linesize(0)
        val rowBytes = width * 4
        val data = input.data(0)
        if (stride == rowBytes) {
            data.put(frame.rgba, 0, rowBytes * height)
        } else {
            for (y in 0 until height) data.position(y.toLong() * stride).put(frame.rgba, y * rowBytes, rowBytes)
        }
        input.pts(frame.index)
        filters!!.push(input)
        drain()
    }

    private fun drain() {
        val filters = filters!!
        val encoder = encoder!!
        val filtered = filtered!!
        while (filters.pull(filtered)) {
            try {
                encoder.encode(filtered, ::write)
            } finally {
                avutil.av_frame_unref(filtered)
            }
        }
    }

    private fun write(packet: AVPacket) {
        output!!.write(packet, encoder!!.context.time_base(), stream!!)
    }

    override fun close() {
        try {
            val filters = filters ?: return
            filters.push(null)
            drain()
            encoder!!.encode(null, ::write)
            output!!.finish()
        } finally {
            release()
        }
    }

    private fun release() {
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

    private fun tag(text: String): Int =
        text[0].code or (text[1].code shl 8) or (text[2].code shl 16) or (text[3].code shl 24)
}
