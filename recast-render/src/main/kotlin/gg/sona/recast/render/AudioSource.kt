package gg.sona.recast.render


interface AudioSource : AutoCloseable {
    fun ffmpegInputArguments(): List<String>

    override fun close() {}
}
