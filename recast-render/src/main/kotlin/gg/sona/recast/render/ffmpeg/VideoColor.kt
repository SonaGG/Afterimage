package gg.sona.recast.render.ffmpeg

import org.bytedeco.ffmpeg.avcodec.AVCodecContext
import org.bytedeco.ffmpeg.avutil.AVFrame
import org.bytedeco.ffmpeg.global.avutil

data class VideoColor(val primaries: Int, val transfer: Int, val matrix: Int, val range: Int) {

    val filterMatrix: String
        get() = when (matrix) {
            avutil.AVCOL_SPC_BT709 -> "bt709"
            avutil.AVCOL_SPC_BT2020_NCL -> "bt2020"
            else -> "bt601"
        }

    val filterRange: String get() = if (range == avutil.AVCOL_RANGE_JPEG) "pc" else "tv"

    fun apply(context: AVCodecContext) {
        context.color_primaries(primaries)
        context.color_trc(transfer)
        context.colorspace(matrix)
        context.color_range(range)
    }

    fun tagRgbSource(frame: AVFrame) {
        frame.color_primaries(primaries)
        frame.color_trc(transfer)
        frame.colorspace(avutil.AVCOL_SPC_RGB)
        frame.color_range(avutil.AVCOL_RANGE_JPEG)
    }

    fun tagGraySource(frame: AVFrame) {
        frame.color_primaries(primaries)
        frame.color_trc(transfer)
        frame.colorspace(matrix)
        frame.color_range(avutil.AVCOL_RANGE_JPEG)
    }

    companion object {
        val BT709_LIMITED = VideoColor(
            avutil.AVCOL_PRI_BT709,
            avutil.AVCOL_TRC_BT709,
            avutil.AVCOL_SPC_BT709,
            avutil.AVCOL_RANGE_MPEG,
        )
    }
}
