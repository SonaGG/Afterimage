package gg.sona.recast.render


enum class ExportFormat(
    val label: String,
    val extension: String,
    val codec: String,
    val sequence: Boolean = false,
    val supportsAudio: Boolean = true,
    val supportsBitrate: Boolean = true,
    val description: String,
) {
    MP4_H264(
        "MP4 (H.264)",
        "mp4",
        "libx264",
        description = "Plays everywhere. Best default for YouTube, Discord and editing."
    ),
    MP4_H265(
        "MP4 (H.265)",
        "mp4",
        "libx265",
        description = "About half the size of H.264 at the same quality; slower to encode, needs a modern player."
    ),
    WEBM_VP9("WebM (VP9)", "webm", "libvpx-vp9", description = "Open format, great for the web. Slow to encode."),
    MOV_PRORES(
        "MOV (ProRes)",
        "mov",
        "prores_ks",
        supportsBitrate = false,
        description = "Huge, near-lossless files for editing in Premiere, Resolve or Final Cut."
    ),
    GIF(
        "GIF",
        "gif",
        "gif",
        supportsAudio = false,
        supportsBitrate = false,
        description = "Looping animated GIF with a generated palette. Keep it small and short."
    ),
    PNG_SEQUENCE(
        "PNG sequence",
        "png",
        "png",
        sequence = true,
        supportsAudio = false,
        supportsBitrate = false,
        description = "Lossless frames, one file each. Works without ffmpeg and supports depth maps."
    ),
    JPEG_SEQUENCE(
        "JPEG sequence",
        "jpg",
        "mjpeg",
        sequence = true,
        supportsAudio = false,
        supportsBitrate = false,
        description = "Small frames, one file each. Works without ffmpeg."
    );

    val needsFfmpeg: Boolean get() = !sequence
}
