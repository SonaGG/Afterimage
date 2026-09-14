package gg.sona.afterimage.render.ffmpeg

import gg.sona.afterimage.clip.export.ExportJob
import gg.sona.afterimage.core.log.AfterimageLog
import org.bytedeco.ffmpeg.global.avcodec
import org.bytedeco.ffmpeg.global.avfilter
import org.bytedeco.ffmpeg.global.avformat
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.ffmpeg.global.swresample
import org.bytedeco.ffmpeg.global.swscale
import org.bytedeco.javacpp.Loader
import org.bytedeco.javacpp.Pointer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

class FfmpegRuntime(root: Path) {
    private val logger = AfterimageLog.logger("afterimage.ffmpeg")
    private val build = FfmpegBuild.current()

    val directory: Path = System.getenv("AFTERIMAGE_FFMPEG")?.takeIf { it.isNotBlank() }?.let { Paths.get(it) }
        ?: root.resolve("ffmpeg").resolve(FfmpegBuild.FFMPEG)

    @Volatile
    var available: Boolean = false
        private set

    @Volatile
    var version: String? = null
        private set

    @Volatile
    private var encoderCache: Set<String>? = null

    @Volatile
    private var warming = false

    val downloadSupported: Boolean get() = build != null

    @Synchronized
    fun load(): Boolean {
        if (available) return true
        if (!Files.isDirectory(directory)) return false
        try {
            System.setProperty("org.bytedeco.javacpp.platform.preloadpath", directory.toString())
            for (library in LIBRARIES) Loader.load(library)
            FfmpegLog.install()
            version = avutil.av_version_info().string
            available = true
        } catch (error: Throwable) {
            logger.warn("Afterimage could not load ffmpeg from $directory", error)
            return false
        }
        return true
    }

    fun encoders(): Set<String> {
        encoderCache?.let { return it }
        if (!available) return emptySet()
        if (!warming) {
            warming = true
            Thread({
                try {
                    probeEncoders()
                } finally {
                    warming = false
                }
            }, "afterimage-ffmpeg-probe").apply { isDaemon = true }.start()
        }
        return emptySet()
    }

    fun probeEncoders(): Set<String> {
        encoderCache?.let { return it }
        if (!available) return emptySet()
        val result = LinkedHashSet<String>()
        val opaque = Pointer()
        while (true) {
            val codec = avcodec.av_codec_iterate(opaque) ?: break
            if (avcodec.av_codec_is_encoder(codec) == 0 || codec.type() != avutil.AVMEDIA_TYPE_VIDEO) continue
            val name = codec.name().string
            val hardware = HARDWARE_SUFFIXES.any { name.endsWith(it) }
            if (!hardware || VideoEncoder.probe(name)) result += name
        }
        encoderCache = result
        return result
    }

    fun downloadJob(): ExportJob {
        val build = checkNotNull(build) { "no ffmpeg build for ${Loader.getPlatform()}" }
        return FfmpegDownloadJob(build, directory) {
            check(load()) { "downloaded ffmpeg does not load" }
            version ?: "unknown"
        }
    }

    private companion object {
        val LIBRARIES = listOf(
            avutil::class.java,
            swresample::class.java,
            swscale::class.java,
            avcodec::class.java,
            avformat::class.java,
            avfilter::class.java,
        )
        val HARDWARE_SUFFIXES = listOf(
            "_nvenc", "_amf", "_qsv", "_videotoolbox", "_vaapi", "_mf", "_vulkan", "_d3d12va", "_d3d11va", "_v4l2m2m", "_omx", "_mediacodec"
        )
    }
}
