package gg.sona.afterimage.editor.imgui

import gg.sona.afterimage.core.time.Nanos

class TimelineView(ui: UiPreferences) {
    var zoom: Double = 1.0
    var offsetNanos: Long = 0L
    var snapToTicks: Boolean = true
    var followPlayhead: Boolean = true
    var renderFps: Int = 60
    val shownLanes: UiPreferences.PersistedSet = ui.shownLanes

    fun frameNanos(): Long = Nanos.PER_SECOND / renderFps.coerceAtLeast(1)
}
