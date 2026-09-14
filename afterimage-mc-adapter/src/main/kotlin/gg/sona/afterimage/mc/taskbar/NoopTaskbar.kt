package gg.sona.afterimage.mc.taskbar

object NoopTaskbar : ITaskbar {
    override fun close() = Unit
    override fun reset() = Unit
    override fun setProgress(count: Long, outOf: Long) = Unit
    override fun setPaused() = Unit
    override fun setNormal() = Unit
}
