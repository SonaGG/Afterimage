package gg.sona.recast.editor.imgui

import gg.sona.recast.core.time.Nanos
import gg.sona.recast.editor.LaneKind

class TimelineView {
    var zoom: Double = 1.0
    var offsetNanos: Long = 0L
    var snapToTicks: Boolean = true
    var followPlayhead: Boolean = true
    var renderFps: Int = 60
    val shownLanes: MutableSet<LaneKind> = mutableSetOf()
    val hiddenLanes: MutableSet<LaneKind> = mutableSetOf()

    fun frameNanos(): Long = Nanos.PER_SECOND / renderFps.coerceAtLeast(1)
}
