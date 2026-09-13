package gg.sona.recast.mc

class FfmpegBuild private constructor(classifier: String, val binary: String) {
    val url = "$REPOSITORY/org/bytedeco/ffmpeg/$VERSION/ffmpeg-$VERSION-$classifier-gpl.jar"
    val prefix = "org/bytedeco/ffmpeg/$classifier-gpl/"

    companion object {
        const val VERSION = "8.1.2-1.5.14"
        private const val REPOSITORY = "https://repo1.maven.org/maven2"

        fun current(): FfmpegBuild? = of(System.getProperty("os.name"), System.getProperty("os.arch"))

        fun of(osName: String, osArch: String): FfmpegBuild? {
            val os = osName.lowercase()
            val arch = when (osArch.lowercase()) {
                "aarch64", "arm64" -> "arm64"
                "amd64", "x86_64", "x64" -> "x86_64"
                else -> return null
            }
            return when {
                os.startsWith("windows") -> if (arch == "x86_64") FfmpegBuild("windows-x86_64", "ffmpeg.exe") else null
                os.startsWith("mac") || os.startsWith("darwin") -> FfmpegBuild("macosx-$arch", "ffmpeg")
                os.startsWith("linux") -> FfmpegBuild("linux-$arch", "ffmpeg")
                else -> null
            }
        }
    }
}
