package gg.sona.afterimage.mc.common.taskbar

interface ITaskbar {
    fun close()
    fun reset()
    fun setProgress(count: Long, outOf: Long)
    fun setPaused()
    fun setNormal()
}
