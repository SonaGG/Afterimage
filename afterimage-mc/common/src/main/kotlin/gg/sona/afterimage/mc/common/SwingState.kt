package gg.sona.afterimage.mc.common

import gg.sona.afterimage.core.time.Nanos

class SwingState(val swinging: Boolean, val ticks: Int, val progress: Float, val lastProgress: Float) {
    companion object {
        private const val MAX_TICKS = 1 shl 20
        val IDLE = SwingState(false, 0, 0f, 0f)

        fun at(swingNanos: Long, tickNanos: Long, duration: Int): SwingState {
            val elapsed = ticksSince(swingNanos, tickNanos)
            return when {
                elapsed == 0 -> SwingState(true, -1, 0f, 0f)
                elapsed <= duration -> SwingState(true, elapsed - 1, (elapsed - 1).toFloat() / duration, (elapsed - 2).coerceAtLeast(0).toFloat() / duration)
                elapsed == duration + 1 -> SwingState(false, 0, 0f, (duration - 1).toFloat() / duration)
                else -> IDLE
            }
        }

        fun duration(base: Int, hasteAmplifier: Int?, fatigueAmplifier: Int?): Int {
            if (hasteAmplifier != null) return (base - (1 + hasteAmplifier)).coerceAtLeast(1)
            if (fatigueAmplifier != null) return base + (1 + fatigueAmplifier) * 2
            return base
        }

        fun ticksSince(eventNanos: Long, tickNanos: Long): Int = when {
            eventNanos == Long.MIN_VALUE -> MAX_TICKS
            tickNanos <= eventNanos -> 0
            else -> ((tickNanos - eventNanos - 1) / Nanos.PER_TICK + 1).coerceAtMost(MAX_TICKS.toLong()).toInt()
        }
    }
}
