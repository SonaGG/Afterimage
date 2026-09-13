package gg.sona.recast.mc

import gg.sona.recast.clip.export.ExportJob
import gg.sona.recast.render.FfmpegCommand
import org.apache.logging.log4j.LogManager
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit

class FfmpegTools(private val root: Path) {
    private val logger = LogManager.getLogger("Recast")
    private val windows = System.getProperty("os.name").lowercase().startsWith("windows")
    private val binary = if (windows) "ffmpeg.exe" else "ffmpeg"
    private val build = FfmpegBuild.current()

    @Volatile
    var executable: String = "ffmpeg"
        private set

    @Volatile
    var version: String? = null
        private set

    @Volatile
    var available: Boolean = false
        private set

    @Volatile
    private var encoderCache: Set<String>? = null

    val downloadSupported: Boolean get() = build != null
    val installDirectory: Path get() = root.resolve("ffmpeg").resolve(FfmpegBuild.VERSION)

    fun locate(): Boolean {
        val candidates = ArrayList<String>()
        System.getenv("RECAST_FFMPEG")?.takeIf { it.isNotBlank() }?.let { candidates += it }
        candidates += installDirectory.resolve(binary).toString()
        candidates += "ffmpeg"
        val home = System.getProperty("user.home")
        if (windows) {
            listOfNotNull(
                System.getenv("LOCALAPPDATA")?.let { "$it\\Microsoft\\WinGet\\Links\\ffmpeg.exe" },
                "C:\\ffmpeg\\bin\\ffmpeg.exe",
                System.getenv("ProgramFiles")?.let { "$it\\ffmpeg\\bin\\ffmpeg.exe" },
                "C:\\ProgramData\\chocolatey\\bin\\ffmpeg.exe",
                "$home\\scoop\\shims\\ffmpeg.exe",
            ).forEach { candidates += it }
        } else {
            candidates += listOf(
                "/opt/homebrew/bin/ffmpeg",
                "/usr/local/bin/ffmpeg",
                "/opt/local/bin/ffmpeg",
                "/usr/bin/ffmpeg",
                "/snap/bin/ffmpeg",
                "$home/.local/bin/ffmpeg",
            )
        }
        if (candidates.any { adopt(it) }) return true
        available = false
        version = null
        return false
    }

    private fun adopt(candidate: String): Boolean {
        val probed = probe(candidate) ?: return false
        executable = candidate
        version = probed
        available = true
        encoderCache = null
        logger.info("Recast using ffmpeg at {} ({})", candidate, probed)
        return true
    }

    private fun probe(candidate: String): String? = try {
        if (candidate != "ffmpeg" && !Files.exists(Paths.get(candidate))) {
            null
        } else {
            val process = ProcessBuilder(candidate, "-version").redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            if (process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0) output.lineSequence().firstOrNull()
                ?.removePrefix("ffmpeg version ")?.substringBefore(' ') ?: "unknown" else null
        }
    } catch (_: Throwable) {
        null
    }

    @Volatile
    private var warming = false

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
            }, "recast-ffmpeg-probe").apply { isDaemon = true }.start()
        }
        return emptySet()
    }

    fun probeEncoders(): Set<String> {
        encoderCache?.let { return it }
        if (!available) return emptySet()
        val listed = try {
            val process = ProcessBuilder(executable, "-hide_banner", "-encoders").redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor(10, TimeUnit.SECONDS)
            FfmpegCommand.parseEncoders(output)
        } catch (error: Throwable) {
            logger.warn("Recast could not list ffmpeg encoders", error)
            emptySet()
        }
        val result = LinkedHashSet<String>()
        for (encoder in listed) {
            val hardware = HARDWARE_SUFFIXES.any { encoder.endsWith(it) }
            if (!hardware || probeEncoder(encoder)) result += encoder
        }
        encoderCache = result
        return result
    }

    private fun probeEncoder(encoder: String): Boolean = try {
        val process = ProcessBuilder(
            executable,
            "-hide_banner",
            "-loglevel",
            "error",
            "-f",
            "lavfi",
            "-i",
            "nullsrc=s=256x144:r=30:d=0.2",
            "-pix_fmt",
            "yuv420p",
            "-c:v",
            encoder,
            "-f",
            "null",
            "-"
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        val finished = process.waitFor(20, TimeUnit.SECONDS)
        if (!finished) process.destroyForcibly()
        val ok = finished && process.exitValue() == 0
        if (!ok) logger.info(
            "Recast: ffmpeg encoder {} is listed but does not work here: {}",
            encoder,
            output.trim().lines().firstOrNull() ?: "no output"
        )
        ok
    } catch (_: Throwable) {
        false
    }

    fun downloadJob(): ExportJob {
        val build = checkNotNull(build) {
            "no ffmpeg download for ${System.getProperty("os.name")} ${System.getProperty("os.arch")}; install ffmpeg and set RECAST_FFMPEG"
        }
        return FfmpegDownloadJob(build, installDirectory) { installed ->
            check(adopt(installed.toString())) { "downloaded ffmpeg does not run" }
            version ?: "unknown"
        }
    }

    private companion object {
        val HARDWARE_SUFFIXES = listOf("_nvenc", "_amf", "_qsv", "_videotoolbox", "_vaapi", "_mf")
    }
}
