package gg.sona.recast.replay.state.shadow

import gg.sona.recast.core.collect.IntObjectMap
import gg.sona.recast.core.time.Nanos

class SwingState(val swinging: Boolean, val ticks: Int, val progress: Float, val lastProgress: Float) {
    companion object {
        private const val HASTE = 3
        private const val MINING_FATIGUE = 4
        private const val MAX_TICKS = 1 shl 20
        val IDLE = SwingState(false, 0, 0f, 0f)

        fun at(swingNanos: Long, tickNanos: Long, duration: Int): SwingState {
            val elapsed = ticksSince(swingNanos, tickNanos)
            return when {
                elapsed == 0 -> SwingState(true, -1, 0f, 0f)
                elapsed <= duration -> SwingState(
                    true,
                    elapsed - 1,
                    (elapsed - 1).toFloat() / duration,
                    (elapsed - 2).coerceAtLeast(0).toFloat() / duration
                )

                elapsed == duration + 1 -> SwingState(false, 0, 0f, (duration - 1).toFloat() / duration)
                else -> IDLE
            }
        }

        fun duration(effects: IntObjectMap<ShadowEffect>, nanos: Long): Int {
            val haste = effects[HASTE]?.takeIf { it.remainingTicks(nanos) > 0 }
            if (haste != null) return (6 - (1 + haste.amplifier)).coerceAtLeast(1)
            val fatigue = effects[MINING_FATIGUE]?.takeIf { it.remainingTicks(nanos) > 0 }
            if (fatigue != null) return 6 + (1 + fatigue.amplifier) * 2
            return 6
        }

        fun ticksSince(eventNanos: Long, tickNanos: Long): Int = when {
            eventNanos == Long.MIN_VALUE -> MAX_TICKS
            tickNanos <= eventNanos -> 0
            else -> ((tickNanos - eventNanos - 1) / Nanos.PER_TICK + 1).coerceAtMost(MAX_TICKS.toLong()).toInt()
        }
    }
}
