package gg.sona.recast.clip.export

interface ExportListener {
    fun onStateChanged(handle: ExportHandle) {}
    fun onProgress(handle: ExportHandle) {}
}