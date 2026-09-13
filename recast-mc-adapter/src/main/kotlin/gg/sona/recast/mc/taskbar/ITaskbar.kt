package gg.sona.recast.mc.taskbar

interface ITaskbar {
    fun close()
    fun reset()
    fun setProgress(count: Long, outOf: Long)
    fun setPaused()
    fun setNormal()
}
