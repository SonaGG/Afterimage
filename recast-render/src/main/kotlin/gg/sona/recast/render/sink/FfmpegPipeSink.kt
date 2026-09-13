package gg.sona.recast.render.sink

import gg.sona.recast.render.AudioSource
import gg.sona.recast.render.ExportSettings
import gg.sona.recast.render.FfmpegCommand
import gg.sona.recast.render.RenderedFrame
import java.io.BufferedOutputStream
import java.io.OutputStream
import java.nio.file.Files
import java.util.concurrent.TimeUnit

class FfmpegPipeSink(
    private val ffmpegExecutable: String = "ffmpeg",
    private val audio: AudioSource? = null,
    private val encoders: Set<String> = emptySet(),
    private val extraArguments: List<String> = emptyList(),
) : FrameSink {

    private var process: Process? = null
    private var stdin: OutputStream? = null
    private var frameBytes = 0
    private var stderr: StringBuilder = StringBuilder()
    private var reader: Thread? = null

    override fun begin(settings: ExportSettings) {
        Files.createDirectories(settings.output.toAbsolutePath().parent)
        val command = FfmpegCommand.build(
            ffmpegExecutable,
            settings.copy(extraArguments = settings.extraArguments + extraArguments),
            audio,
            encoders
        )
        val started = ProcessBuilder(command).redirectErrorStream(true).start()
        process = started
        stderr = StringBuilder()
        reader = Thread({
            runCatching {
                started.inputStream.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        if (stderr.length < 8000) stderr.append(line).append('\n')
                    }
                }
            }
        }, "recast-ffmpeg-log").apply { isDaemon = true }.also { it.start() }
        stdin = BufferedOutputStream(started.outputStream, 1 shl 20)
        frameBytes = settings.width * settings.height * 4
    }

    override fun accept(frame: RenderedFrame) {
        val out = stdin ?: error("sink not started")
        try {
            out.write(frame.rgba, 0, frameBytes)
        } catch (error: java.io.IOException) {
            val alive = process?.isAlive == true
            if (alive) {
                process?.waitFor(2, TimeUnit.SECONDS)
            }
            reader?.join(1500)
            throw IllegalStateException("ffmpeg stopped accepting frames: ${summary()}", error)
        }
    }

    private fun summary(): String {
        val lines = stderr.toString().lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return "no output from ffmpeg"
        val interesting = lines.filter { !it.startsWith("[out#") && !it.contains("Nothing was written") }.take(3)
        return (interesting.ifEmpty { lines.take(3) }).joinToString(" | ")
    }

    fun failureSummary(): String = summary()

    override fun close() {
        runCatching { stdin?.flush() }
        runCatching { stdin?.close() }
        val exit = process?.waitFor() ?: 0
        reader?.join(2000)
        audio?.close()
        if (exit != 0) throw IllegalStateException("ffmpeg exited with status $exit: ${summary()}")
    }
}
