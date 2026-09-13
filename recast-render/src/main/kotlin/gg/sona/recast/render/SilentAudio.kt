package gg.sona.recast.render


object SilentAudio : AudioSource {
    override fun ffmpegInputArguments(): List<String> =
        listOf("-f", "lavfi", "-i", "anullsrc=channel_layout=stereo:sample_rate=48000", "-shortest")
}
