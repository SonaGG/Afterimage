package gg.sona.recast.capture.state

fun interface StateTrackerFactory {
    fun create(): StateTracker
}
