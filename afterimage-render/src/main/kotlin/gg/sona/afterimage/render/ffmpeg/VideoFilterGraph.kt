package gg.sona.afterimage.render.ffmpeg

import org.bytedeco.ffmpeg.avfilter.AVFilterContext
import org.bytedeco.ffmpeg.avfilter.AVFilterGraph
import org.bytedeco.ffmpeg.avutil.AVFrame
import org.bytedeco.ffmpeg.global.avfilter
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacpp.BytePointer

class VideoFilterGraph(width: Int, height: Int, fps: Int, inputFormat: Int, description: String) : AutoCloseable {
    private val graph: AVFilterGraph = avfilter.avfilter_graph_alloc()
    private val source = AVFilterContext(null)
    private val sink = AVFilterContext(null)

    init {
        try {
            val arguments = "video_size=${width}x$height:pix_fmt=$inputFormat:time_base=1/$fps:frame_rate=$fps:pixel_aspect=1/1"
            Libav.check(
                avfilter.avfilter_graph_create_filter(source, avfilter.avfilter_get_by_name("buffer"), "in", arguments, null, graph),
                "could not create filter input"
            )
            Libav.check(
                avfilter.avfilter_graph_create_filter(sink, avfilter.avfilter_get_by_name("buffersink"), "out", null, null, graph),
                "could not create filter output"
            )
            val outputs = avfilter.avfilter_inout_alloc()
            val inputs = avfilter.avfilter_inout_alloc()
            outputs.name(avutil.av_strdup(BytePointer("in")))
            outputs.filter_ctx(source)
            outputs.pad_idx(0)
            outputs.next(null)
            inputs.name(avutil.av_strdup(BytePointer("out")))
            inputs.filter_ctx(sink)
            inputs.pad_idx(0)
            inputs.next(null)
            try {
                Libav.check(
                    avfilter.avfilter_graph_parse_ptr(graph, description, inputs, outputs, null),
                    "could not parse video filters '$description'"
                )
            } finally {
                avfilter.avfilter_inout_free(inputs)
                avfilter.avfilter_inout_free(outputs)
            }
            Libav.check(avfilter.avfilter_graph_config(graph, null), "could not configure video filters '$description'")
        } catch (error: Throwable) {
            close()
            throw error
        }
    }

    val outputFormat: Int get() = avfilter.av_buffersink_get_format(sink)

    fun push(frame: AVFrame?) {
        Libav.check(
            avfilter.av_buffersrc_add_frame_flags(source, frame, avfilter.AV_BUFFERSRC_FLAG_KEEP_REF),
            "video filter rejected frame"
        )
    }

    fun pull(into: AVFrame): Boolean {
        val code = avfilter.av_buffersink_get_frame(sink, into)
        if (code == Libav.EAGAIN || code == avutil.AVERROR_EOF) return false
        Libav.check(code, "video filter failed")
        return true
    }

    override fun close() {
        avfilter.avfilter_graph_free(graph)
    }
}
