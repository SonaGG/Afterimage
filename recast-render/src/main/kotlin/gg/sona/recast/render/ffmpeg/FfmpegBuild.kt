package gg.sona.recast.render.ffmpeg

import org.bytedeco.javacpp.Loader

class FfmpegBuild private constructor(val platform: String) {
    class Archive(val url: String, val prefix: String)

    val archives = listOf(
        Archive(
            "$REPOSITORY/org/bytedeco/javacpp/$JAVACPP/javacpp-$JAVACPP-$platform.jar",
            "org/bytedeco/javacpp/$platform/"
        ),
        Archive(
            "$REPOSITORY/org/bytedeco/ffmpeg/$FFMPEG/ffmpeg-$FFMPEG-$platform-gpl.jar",
            "org/bytedeco/ffmpeg/$platform-gpl/"
        ),
    )

    companion object {
        const val JAVACPP = "1.5.14"
        const val FFMPEG = "8.1.2-$JAVACPP"
        private const val REPOSITORY = "https://repo1.maven.org/maven2"
        private val PLATFORMS = setOf("windows-x86_64", "macosx-x86_64", "macosx-arm64", "linux-x86_64", "linux-arm64")

        fun current(): FfmpegBuild? = of(Loader.getPlatform())

        fun of(platform: String): FfmpegBuild? = if (platform in PLATFORMS) FfmpegBuild(platform) else null
    }
}
