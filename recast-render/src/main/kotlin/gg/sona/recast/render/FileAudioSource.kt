package gg.sona.recast.render

import java.nio.file.Path
import java.util.*

class FileAudioSource(
    private val file: Path,
    private val offsetSeconds: Double = 0.0,
    private val volume: Double = 1.0
) : AudioSource {
    override fun ffmpegInputArguments(): List<String> {
        val arguments = ArrayList<String>()
        if (offsetSeconds > 0.0) arguments += listOf("-ss", String.format(Locale.ROOT, "%.3f", offsetSeconds))
        arguments += listOf("-i", file.toString())
        arguments += listOf("-shortest")
        if (volume != 1.0) arguments += listOf("-af", String.format(Locale.ROOT, "volume=%.3f", volume))
        return arguments
    }
}
