package gg.sona.afterimage.capture.state

fun interface StateTrackerFactory {
    fun create(): StateTracker
}
