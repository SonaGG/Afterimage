package gg.sona.recast.core.time

object Nanos {
    const val PER_MILLI = 1_000_000L
    const val PER_SECOND = 1_000_000_000L
    const val PER_TICK = PER_SECOND / 20L

    fun ofMillis(millis: Long): Long = millis * PER_MILLI
    fun ofSeconds(seconds: Long): Long = seconds * PER_SECOND
    fun ofSeconds(seconds: Double): Long = (seconds * PER_SECOND).toLong()
}
