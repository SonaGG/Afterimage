package gg.sona.afterimage.core.time

object SystemClock : Clock {
    override fun nanos(): Long = System.nanoTime()
}
