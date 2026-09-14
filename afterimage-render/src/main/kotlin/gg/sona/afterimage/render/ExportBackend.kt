package gg.sona.afterimage.render

import gg.sona.afterimage.clip.export.ExportHandle
import gg.sona.afterimage.clip.export.ExportQueue

interface ExportBackend {
    val windowWidth: Int

    val windowHeight: Int

    val ffmpegAvailable: Boolean

    val ffmpegPath: String

    val ffmpegDownloadSupported: Boolean

    val ffmpegVersion: String?

    fun encoders(): Set<String>

    fun downloadFfmpeg(): ExportHandle?

    fun submit(request: ExportRequest): ExportHandle?

    fun queue(): ExportQueue

    fun live(): ExportLive? = null
}
