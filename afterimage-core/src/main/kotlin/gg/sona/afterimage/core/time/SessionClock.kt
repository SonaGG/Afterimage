package gg.sona.afterimage.core.time

class SessionClock(private val source: Clock, private val originNanos: Long = source.nanos()) : Clock {
    override fun nanos(): Long = source.nanos() - originNanos
}
