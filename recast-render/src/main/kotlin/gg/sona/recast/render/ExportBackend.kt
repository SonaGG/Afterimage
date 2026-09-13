package gg.sona.recast.render

import gg.sona.recast.clip.export.ExportHandle
import gg.sona.recast.clip.export.ExportQueue

interface ExportBackend {
    val windowWidth: Int

    val windowHeight: Int

    val ffmpegAvailable: Boolean

    val ffmpegExecutable: String

    val ffmpegDownloadSupported: Boolean

    val ffmpegVersion: String?

    fun encoders(): Set<String>

    fun relocateFfmpeg(): Boolean

    fun downloadFfmpeg(): ExportHandle?

    fun submit(request: ExportRequest): ExportHandle?

    fun queue(): ExportQueue

    fun live(): ExportLive? = null
}
