package gg.sona.recast.core.time

object SystemClock : Clock {
    override fun nanos(): Long = System.nanoTime()
}
