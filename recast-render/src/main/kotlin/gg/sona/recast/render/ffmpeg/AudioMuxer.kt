package gg.sona.recast.render.ffmpeg

import gg.sona.recast.render.AudioMix
import gg.sona.recast.render.ExportEncoding
import gg.sona.recast.render.ExportFormat
import org.bytedeco.ffmpeg.avcodec.AVCodecContext
import org.bytedeco.ffmpeg.avcodec.AVPacket
import org.bytedeco.ffmpeg.avformat.AVFormatContext
import org.bytedeco.ffmpeg.avformat.AVStream
import org.bytedeco.ffmpeg.avutil.AVDictionary
import org.bytedeco.ffmpeg.avutil.AVFrame
import org.bytedeco.ffmpeg.global.avcodec
import org.bytedeco.ffmpeg.global.avformat
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.ffmpeg.global.swresample
import org.bytedeco.ffmpeg.swresample.SwrContext
import org.bytedeco.javacpp.FloatPointer
import java.nio.file.Path

class AudioMuxer(private val mix: AudioMix, private val format: ExportFormat) {

    fun mux(video: Path, output: Path, containerOptions: Map<String, String>) {
        FfmpegLog.clear()
        val input = AVFormatContext(null)
        Libav.check(avformat.avformat_open_input(input, video.toString(), null, null), "could not open ${video.fileName}")
        try {
            Libav.check(avformat.avformat_find_stream_info(input, null as AVDictionary?), "could not read ${video.fileName}")
            val videoIndex = (0 until input.nb_streams()).firstOrNull {
                input.streams(it).codecpar().codec_type() == avutil.AVMEDIA_TYPE_VIDEO
            } ?: throw IllegalStateException("${video.fileName} has no video stream")
            MediaOutput(output).use { out ->
                val source = input.streams(videoIndex)
                val videoStream = out.addStream()
                Libav.check(avcodec.avcodec_parameters_copy(videoStream.codecpar(), source.codecpar()), "video stream")
                videoStream.time_base(source.time_base())
                AudioEncoder(out).use { audio ->
                    out.writeHeader(containerOptions)
                    val packet = avcodec.av_packet_alloc()
                    try {
                        while (true) {
                            val code = avformat.av_read_frame(input, packet)
                            if (code == avutil.AVERROR_EOF) break
                            Libav.check(code, "could not read ${video.fileName}")
                            try {
                                if (packet.stream_index() != videoIndex) continue
                                val seconds = packet.pts() * avutil.av_q2d(source.time_base())
                                audio.encodeUntil(seconds)
                                out.write(packet, source.time_base(), videoStream)
                            } finally {
                                avcodec.av_packet_unref(packet)
                            }
                        }
                    } finally {
                        avcodec.av_packet_free(packet)
                    }
                    audio.finish()
                }
                out.finish()
            }
        } finally {
            avformat.avformat_close_input(input)
        }
    }

    private inner class AudioEncoder(private val out: MediaOutput) : AutoCloseable {
        private val codecName = ExportEncoding.audioCodec(format)
        private val codec = avcodec.avcodec_find_encoder_by_name(codecName)
            ?: throw IllegalStateException("encoder $codecName is not available")
        private val context: AVCodecContext = avcodec.avcodec_alloc_context3(codec)
        private var stream: AVStream
        private val layout = Libav.stereo()
        private val swr = SwrContext(null)
        private val packet: AVPacket = avcodec.av_packet_alloc()
        private val source: AVFrame = avutil.av_frame_alloc()
        private val target: AVFrame = avutil.av_frame_alloc()
        private var chunk = 0
        private var interleaved: FloatArray
        private var position = 0

        init {
            try {
                context.sample_rate(mix.sampleRate)
                context.sample_fmt(sampleFormat())
                context.time_base(avutil.av_make_q(1, mix.sampleRate))
                avutil.av_channel_layout_copy(context.ch_layout(), layout)
                context.bit_rate(ExportEncoding.audioBitrate(format))
                if (out.globalHeader) context.flags(context.flags() or avcodec.AV_CODEC_FLAG_GLOBAL_HEADER)
                Libav.check(avcodec.avcodec_open2(context, codec, null as AVDictionary?), "could not open $codecName")
                chunk = context.frame_size().takeIf { it > 0 } ?: 1024
                interleaved = FloatArray(chunk * 2)
                stream = out.addStream()
                Libav.check(avcodec.avcodec_parameters_from_context(stream.codecpar(), context), "audio stream")
                stream.time_base(context.time_base())
                Libav.check(
                    swresample.swr_alloc_set_opts2(
                        swr, layout, context.sample_fmt(), mix.sampleRate, layout, avutil.AV_SAMPLE_FMT_FLT, mix.sampleRate, 0, null
                    ), "resampler"
                )
                Libav.check(swresample.swr_init(swr), "resampler")
                source.format(avutil.AV_SAMPLE_FMT_FLT)
                source.sample_rate(mix.sampleRate)
                source.nb_samples(chunk)
                avutil.av_channel_layout_copy(source.ch_layout(), layout)
                Libav.check(avutil.av_frame_get_buffer(source, 0), "audio buffer")
                target.format(context.sample_fmt())
                target.sample_rate(mix.sampleRate)
                target.nb_samples(chunk)
                avutil.av_channel_layout_copy(target.ch_layout(), layout)
                Libav.check(avutil.av_frame_get_buffer(target, 0), "audio buffer")
            } catch (error: Throwable) {
                close()
                throw error
            }
        }

        private fun sampleFormat(): Int = when (codecName) {
            "pcm_s16le" -> avutil.AV_SAMPLE_FMT_S16
            "libopus" -> avutil.AV_SAMPLE_FMT_FLT
            else -> avutil.AV_SAMPLE_FMT_FLTP
        }

        fun encodeUntil(seconds: Double) {
            val limit = (seconds * mix.sampleRate).toLong()
            while (position < mix.frames && position < limit) encodeChunk()
        }

        fun finish() {
            while (position < mix.frames) encodeChunk()
            Libav.check(avcodec.avcodec_send_frame(context, null), "audio encoder")
            receive()
        }

        private fun encodeChunk() {
            val count = minOf(chunk, mix.frames - position)
            for (i in 0 until count) {
                interleaved[i * 2] = mix.left[position + i].coerceIn(-1f, 1f)
                interleaved[i * 2 + 1] = mix.right[position + i].coerceIn(-1f, 1f)
            }
            Libav.check(avutil.av_frame_make_writable(source), "audio buffer")
            Libav.check(avutil.av_frame_make_writable(target), "audio buffer")
            FloatPointer(source.data(0)).put(interleaved, 0, count * 2)
            source.nb_samples(count)
            target.nb_samples(count)
            Libav.check(swresample.swr_convert(swr, target.data(), count, source.data(), count), "resampler")
            target.pts(position.toLong())
            Libav.check(avcodec.avcodec_send_frame(context, target), "audio encoder rejected frame")
            position += count
            receive()
        }

        private fun receive() {
            while (true) {
                val code = avcodec.avcodec_receive_packet(context, packet)
                if (code == Libav.EAGAIN || code == avutil.AVERROR_EOF) return
                Libav.check(code, "audio encoder failed")
                try {
                    out.write(packet, context.time_base(), stream)
                } finally {
                    avcodec.av_packet_unref(packet)
                }
            }
        }

        override fun close() {
            avutil.av_frame_free(source)
            avutil.av_frame_free(target)
            avcodec.av_packet_free(packet)
            swresample.swr_free(swr)
            avcodec.avcodec_free_context(context)
            avutil.av_channel_layout_uninit(layout)
        }
    }
}
