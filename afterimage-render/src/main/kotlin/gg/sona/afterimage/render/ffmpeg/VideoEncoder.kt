package gg.sona.afterimage.render.ffmpeg

import org.bytedeco.ffmpeg.avcodec.AVCodec
import org.bytedeco.ffmpeg.avcodec.AVCodecContext
import org.bytedeco.ffmpeg.avcodec.AVPacket
import org.bytedeco.ffmpeg.avutil.AVDictionary
import org.bytedeco.ffmpeg.avutil.AVDictionaryEntry
import org.bytedeco.ffmpeg.avutil.AVFrame
import org.bytedeco.ffmpeg.global.avcodec
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacpp.IntPointer
import org.bytedeco.javacpp.PointerPointer

class VideoEncoder(
    codecName: String,
    width: Int,
    height: Int,
    fps: Int,
    pixelFormat: Int,
    options: Map<String, String> = emptyMap(),
    globalHeader: Boolean = false,
    color: VideoColor? = null,
) : AutoCloseable {
    val codec: AVCodec = avcodec.avcodec_find_encoder_by_name(codecName)
        ?: throw IllegalStateException("encoder $codecName is not available")
    val context: AVCodecContext = avcodec.avcodec_alloc_context3(codec)
    private val packet: AVPacket = avcodec.av_packet_alloc()

    init {
        context.width(width)
        context.height(height)
        context.time_base(avutil.av_make_q(1, fps))
        context.framerate(avutil.av_make_q(fps, 1))
        context.pix_fmt(pixelFormat)
        color?.apply(context)
        context.thread_count(0)
        if (globalHeader) context.flags(context.flags() or avcodec.AV_CODEC_FLAG_GLOBAL_HEADER)
        val dictionary = AVDictionary(null)
        for ((key, value) in options) avutil.av_dict_set(dictionary, key, value, 0)
        try {
            Libav.check(avcodec.avcodec_open2(context, codec, dictionary), "could not open encoder $codecName")
            var entry: AVDictionaryEntry? = null
            while (true) {
                entry = avutil.av_dict_iterate(dictionary, entry) ?: break
                throw IllegalStateException("encoder $codecName does not accept option ${entry.key().string}")
            }
        } catch (error: Throwable) {
            close()
            throw error
        } finally {
            avutil.av_dict_free(dictionary)
        }
    }

    fun encode(frame: AVFrame?, packets: (AVPacket) -> Unit) {
        Libav.check(avcodec.avcodec_send_frame(context, frame), "encoder rejected frame")
        while (true) {
            val code = avcodec.avcodec_receive_packet(context, packet)
            if (code == Libav.EAGAIN || code == avutil.AVERROR_EOF) return
            Libav.check(code, "encoder failed")
            try {
                packets(packet)
            } finally {
                avcodec.av_packet_unref(packet)
            }
        }
    }

    override fun close() {
        avcodec.av_packet_free(packet)
        avcodec.avcodec_free_context(context)
    }

    companion object {
        fun pixelFormats(codec: AVCodec): List<Int> {
            val context = avcodec.avcodec_alloc_context3(codec)
            try {
                val formats = PointerPointer<IntPointer>(1L)
                val count = IntPointer(1L)
                val code = avcodec.avcodec_get_supported_config(
                    context, codec, avcodec.AV_CODEC_CONFIG_PIX_FORMAT, 0, formats, count
                )
                if (code < 0) return emptyList()
                val list = formats.get(IntPointer::class.java, 0) ?: return emptyList()
                return (0 until count.get()).map { list.get(it.toLong()) }
            } finally {
                avcodec.avcodec_free_context(context)
            }
        }

        fun choosePixelFormat(codec: AVCodec, requested: Int): Int {
            val supported = pixelFormats(codec).filter { format ->
                val descriptor = avutil.av_pix_fmt_desc_get(format)
                descriptor != null && descriptor.flags() and avutil.AV_PIX_FMT_FLAG_HWACCEL.toLong() == 0L
            }
            if (supported.isEmpty() || requested in supported) return requested
            return supported.first()
        }

        fun probe(codecName: String): Boolean = try {
            val codec = avcodec.avcodec_find_encoder_by_name(codecName) ?: return false
            val format = choosePixelFormat(codec, avutil.AV_PIX_FMT_YUV420P)
            VideoEncoder(codecName, 256, 144, 30, format).use { encoder ->
                val frame = avutil.av_frame_alloc()
                try {
                    frame.format(format)
                    frame.width(256)
                    frame.height(144)
                    Libav.check(avutil.av_frame_get_buffer(frame, 0), "frame")
                    frame.pts(0)
                    encoder.encode(frame) {}
                    encoder.encode(null) {}
                } finally {
                    avutil.av_frame_free(frame)
                }
            }
            true
        } catch (_: Throwable) {
            false
        }
    }
}
