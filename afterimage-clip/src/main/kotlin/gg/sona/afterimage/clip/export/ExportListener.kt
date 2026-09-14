package gg.sona.afterimage.clip.export

interface ExportListener {
    fun onStateChanged(handle: ExportHandle) {}
    fun onProgress(handle: ExportHandle) {}
}