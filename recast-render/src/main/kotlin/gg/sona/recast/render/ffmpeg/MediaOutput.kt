package gg.sona.recast.render.ffmpeg

import org.bytedeco.ffmpeg.avcodec.AVPacket
import org.bytedeco.ffmpeg.avformat.AVFormatContext
import org.bytedeco.ffmpeg.avformat.AVIOContext
import org.bytedeco.ffmpeg.avformat.AVStream
import org.bytedeco.ffmpeg.avutil.AVDictionary
import org.bytedeco.ffmpeg.avutil.AVRational
import org.bytedeco.ffmpeg.global.avcodec
import org.bytedeco.ffmpeg.global.avformat
import org.bytedeco.ffmpeg.global.avutil
import java.nio.file.Files
import java.nio.file.Path

class MediaOutput(private val path: Path) : AutoCloseable {
    val context: AVFormatContext = AVFormatContext(null)
    private var open = false
    private var headerWritten = false

    init {
        Files.createDirectories(path.toAbsolutePath().parent)
        Libav.check(
            avformat.avformat_alloc_output_context2(context, null, null as String?, path.toString()),
            "no container for ${path.fileName}"
        )
    }

    val globalHeader: Boolean get() = context.oformat().flags() and avformat.AVFMT_GLOBALHEADER != 0

    fun addStream(): AVStream = avformat.avformat_new_stream(context, null)
        ?: throw IllegalStateException("could not add stream to ${path.fileName}")

    fun writeHeader(options: Map<String, String> = emptyMap()) {
        if (context.oformat().flags() and avformat.AVFMT_NOFILE == 0) {
            val io = AVIOContext(null)
            Libav.check(avformat.avio_open(io, path.toString(), avformat.AVIO_FLAG_WRITE), "could not create $path")
            context.pb(io)
            open = true
        }
        val dictionary = AVDictionary(null)
        for ((key, value) in options) avutil.av_dict_set(dictionary, key, value, 0)
        try {
            Libav.check(avformat.avformat_write_header(context, dictionary), "could not write header of ${path.fileName}")
        } finally {
            avutil.av_dict_free(dictionary)
        }
        headerWritten = true
    }

    fun write(packet: AVPacket, sourceTimeBase: AVRational, stream: AVStream) {
        avcodec.av_packet_rescale_ts(packet, sourceTimeBase, stream.time_base())
        packet.stream_index(stream.index())
        packet.pos(-1)
        Libav.check(avformat.av_interleaved_write_frame(context, packet), "could not write to ${path.fileName}")
    }

    fun finish() {
        if (headerWritten) Libav.check(avformat.av_write_trailer(context), "could not finish ${path.fileName}")
        headerWritten = false
    }

    override fun close() {
        if (open) {
            avformat.avio_closep(context.pb())
            context.pb(null)
        }
        open = false
        avformat.avformat_free_context(context)
    }
}
