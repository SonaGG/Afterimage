package gg.sona.afterimage.render.ffmpeg

import gg.sona.afterimage.render.AudioMix
import org.bytedeco.ffmpeg.avcodec.AVCodec
import org.bytedeco.ffmpeg.avcodec.AVCodecContext
import org.bytedeco.ffmpeg.avformat.AVFormatContext
import org.bytedeco.ffmpeg.avutil.AVDictionary
import org.bytedeco.ffmpeg.avutil.AVFrame
import org.bytedeco.ffmpeg.global.avcodec
import org.bytedeco.ffmpeg.global.avformat
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.ffmpeg.global.swresample
import org.bytedeco.ffmpeg.swresample.SwrContext
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.FloatPointer
import org.bytedeco.javacpp.PointerPointer
import java.nio.file.Path

object AudioFileDecoder {

    fun mixInto(mix: AudioMix, file: Path, offsetSeconds: Double, volume: Double) {
        val format = AVFormatContext(null)
        Libav.check(avformat.avformat_open_input(format, file.toString(), null, null), "could not open ${file.fileName}")
        try {
            Libav.check(avformat.avformat_find_stream_info(format, null as AVDictionary?), "could not read ${file.fileName}")
            val decoder = AVCodec(null)
            val index = Libav.check(
                avformat.av_find_best_stream(format, avutil.AVMEDIA_TYPE_AUDIO, -1, -1, decoder, 0),
                "${file.fileName} has no audio"
            )
            val context = avcodec.avcodec_alloc_context3(decoder)
            try {
                Libav.check(avcodec.avcodec_parameters_to_context(context, format.streams(index).codecpar()), "decoder")
                Libav.check(avcodec.avcodec_open2(context, decoder, null as AVDictionary?), "could not open audio decoder")
                decode(mix, format, index, context, offsetSeconds, volume.toFloat())
            } finally {
                avcodec.avcodec_free_context(context)
            }
        } finally {
            avformat.avformat_close_input(format)
        }
    }

    private fun decode(
        mix: AudioMix,
        format: AVFormatContext,
        index: Int,
        context: AVCodecContext,
        offsetSeconds: Double,
        volume: Float,
    ) {
        val stereo = Libav.stereo()
        val swr = SwrContext(null)
        Libav.check(
            swresample.swr_alloc_set_opts2(
                swr, stereo, avutil.AV_SAMPLE_FMT_FLT, mix.sampleRate,
                context.ch_layout(), context.sample_fmt(), context.sample_rate(), 0, null
            ), "resampler"
        )
        Libav.check(swresample.swr_init(swr), "resampler")
        val packet = avcodec.av_packet_alloc()
        val frame = avutil.av_frame_alloc()
        val capacity = 1 shl 16
        val buffer = BytePointer(capacity * 8L)
        val output = PointerPointer<BytePointer>(1L).put(0L, buffer)
        val samples = FloatArray(capacity * 2)
        var skip = (offsetSeconds.coerceAtLeast(0.0) * mix.sampleRate).toLong()
        var position = 0
        try {
            fun consume(count: Int) {
                var start = 0
                var available = count
                if (skip > 0) {
                    val dropped = minOf(skip, available.toLong()).toInt()
                    skip -= dropped
                    start = dropped
                    available -= dropped
                }
                if (available <= 0) return
                val length = minOf(available, mix.frames - position)
                if (length <= 0) return
                FloatPointer(buffer).get(samples, 0, (start + length) * 2)
                for (i in 0 until length) {
                    mix.left[position + i] += samples[(start + i) * 2] * volume
                    mix.right[position + i] += samples[(start + i) * 2 + 1] * volume
                }
                position += length
            }

            fun convert(input: AVFrame?) {
                while (true) {
                    val produced = Libav.check(
                        if (input != null) swresample.swr_convert(swr, output, capacity, input.data(), input.nb_samples())
                        else swresample.swr_convert(swr, output, capacity, null as PointerPointer<*>?, 0),
                        "resampler"
                    )
                    if (produced <= 0) return
                    consume(produced)
                    if (input != null || produced < capacity) return
                }
            }

            fun receive() {
                while (true) {
                    val code = avcodec.avcodec_receive_frame(context, frame)
                    if (code == Libav.EAGAIN || code == avutil.AVERROR_EOF) return
                    Libav.check(code, "audio decoder failed")
                    try {
                        convert(frame)
                    } finally {
                        avutil.av_frame_unref(frame)
                    }
                    if (position >= mix.frames) return
                }
            }

            while (position < mix.frames) {
                val code = avformat.av_read_frame(format, packet)
                if (code == avutil.AVERROR_EOF) break
                Libav.check(code, "could not read audio")
                try {
                    if (packet.stream_index() != index) continue
                    Libav.check(avcodec.avcodec_send_packet(context, packet), "audio decoder rejected packet")
                } finally {
                    avcodec.av_packet_unref(packet)
                }
                receive()
            }
            if (position < mix.frames) {
                avcodec.avcodec_send_packet(context, null)
                receive()
                convert(null)
            }
        } finally {
            avcodec.av_packet_free(packet)
            avutil.av_frame_free(frame)
            swresample.swr_free(swr)
            avutil.av_channel_layout_uninit(stereo)
        }
    }
}
