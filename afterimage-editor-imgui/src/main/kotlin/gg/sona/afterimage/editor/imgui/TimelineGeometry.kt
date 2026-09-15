package gg.sona.afterimage.editor.imgui

import gg.sona.afterimage.editor.LaneKind

class TimelineGeometry {
    var headerX = 0f
    var originX = 0f
    var width = 1f
    var offsetNanos = 0L
    var visibleNanos = 1L
    var duration = 1L
    var rulerTop = 0f
    var tracksTop = 0f
    var tracksBottom = 0f
    var scrollY = 0f
    var contentHeight = 0f
    var dragActive = false
    var dragDeltaNanos = 0L

    var lanes: List<TrackSpec> = emptyList()
        private set

    private val tops = HashMap<LaneKind, Float>()
    private val heights = HashMap<LaneKind, Float>()

    val right: Float get() = originX + width

    fun layout(visible: List<TrackSpec>, heightOf: (TrackSpec) -> Float) {
        lanes = visible
        tops.clear()
        heights.clear()
        var y = tracksTop - scrollY
        for (lane in visible) {
            val height = heightOf(lane)
            tops[lane.kind] = y
            heights[lane.kind] = height
            y += height
        }
        contentHeight = y - (tracksTop - scrollY)
    }

    fun laneTop(kind: LaneKind): Float = tops[kind] ?: tracksTop

    fun laneHeight(kind: LaneKind): Float = heights[kind] ?: 0f

    fun laneAt(y: Float): TrackSpec? {
        if (y < tracksTop || y >= tracksBottom) return null
        return lanes.firstOrNull { y >= laneTop(it.kind) && y < laneTop(it.kind) + laneHeight(it.kind) }
    }

    fun laneVisible(kind: LaneKind): Boolean {
        val top = tops[kind] ?: return false
        return top + laneHeight(kind) > tracksTop && top < tracksBottom
    }

    fun xAt(nanos: Long): Float = originX + ((nanos - offsetNanos).toDouble() / visibleNanos * width).toFloat()

    fun nanosAt(x: Float): Long = offsetNanos + ((x - originX) / width * visibleNanos).toLong()

    fun shown(nanos: Long, selected: Boolean): Long =
        if (dragActive && selected) maxOf(0L, nanos + dragDeltaNanos) else nanos

    val nanosPerPixel: Double get() = visibleNanos.toDouble() / width

    val leftNanos: Long get() = offsetNanos

    val rightNanos: Long get() = offsetNanos + visibleNanos
}
