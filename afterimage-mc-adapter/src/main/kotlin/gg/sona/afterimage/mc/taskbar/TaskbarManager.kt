package gg.sona.afterimage.mc.taskbar

import gg.sona.afterimage.render.ExportLive

class TaskbarManager {

    private var taskbar: ITaskbar? = null
    private var lastCount = -1L
    private var lastOutOf = -1L

    fun onFrame(live: ExportLive?) {
        val count = live?.framesDone ?: 0L
        val outOf = live?.frameCount ?: 0L
        if (count == lastCount && outOf == lastOutOf) return
        lastCount = count
        lastOutOf = outOf
        val bar = taskbar ?: TaskbarHost.create().also { taskbar = it }
        if (outOf <= 0L) {
            bar.reset()
        } else {
            bar.setNormal()
            bar.setProgress(count.coerceAtMost(outOf), outOf)
        }
    }

    fun shutdown() {
        taskbar?.close()
        taskbar = null
    }
}
