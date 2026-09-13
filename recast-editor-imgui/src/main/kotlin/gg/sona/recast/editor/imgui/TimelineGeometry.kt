package gg.sona.recast.editor.imgui

class TimelineGeometry {
    var headerX = 0f
    var originX = 0f
    var width = 1f
    var offsetNanos = 0L
    var visibleNanos = 1L
    var duration = 1L

    fun xAt(nanos: Long): Float = originX + ((nanos - offsetNanos).toDouble() / visibleNanos * width).toFloat()

    fun nanosAt(x: Float): Long = offsetNanos + ((x - originX) / width * visibleNanos).toLong()

    val nanosPerPixel: Double get() = visibleNanos.toDouble() / width

    val leftNanos: Long get() = offsetNanos

    val rightNanos: Long get() = offsetNanos + visibleNanos
}
