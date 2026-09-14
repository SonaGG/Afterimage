package gg.sona.afterimage.replay.state.shadow

import gg.sona.afterimage.core.time.Nanos
import gg.sona.afterimage.protocol.ItemStack

class HandState(
    val handHeight: Float,
    val lastHandHeight: Float,
    val previousItem: ItemStack?,
    val swing: SwingState,
    val easedYaw: Float,
    val easedPitch: Float,
    val lastEasedYaw: Float,
    val lastEasedPitch: Float,
) {
    companion object {
        private val HAND_HEIGHTS = floatArrayOf(1f, 0.6f, 0.2f, 0f, 0.4f, 0.8f, 1f)
        private const val PREVIOUS_ITEM_TICKS = 3
        private const val EASE_TICKS = 12

        fun at(player: ShadowLocalPlayer, nanos: Long, tickNanos: Long): HandState {
            val heldTicks = SwingState.ticksSince(player.heldChangedAtNanos, tickNanos)
            val handHeight = HAND_HEIGHTS[heldTicks.coerceIn(0, HAND_HEIGHTS.lastIndex)]
            val lastHandHeight = if (heldTicks <= 0) 1f else HAND_HEIGHTS[(heldTicks - 1).coerceAtMost(HAND_HEIGHTS.lastIndex)]
            val previousItem = if (heldTicks < PREVIOUS_ITEM_TICKS) player.heldBefore else null
            val swing = SwingState.at(player.lastSwingNanos, tickNanos, SwingState.duration(player.effects, nanos))

            val rotations = player.history.rotations
            var easedYaw = player.yaw
            var easedPitch = player.pitch
            var lastEasedYaw = easedYaw
            var lastEasedPitch = easedPitch
            if (rotations.size > 0) {
                val start = rotationAt(rotations, tickNanos - EASE_TICKS * Nanos.PER_TICK)
                easedYaw = start.a.toFloat()
                easedPitch = start.b.toFloat()
                for (tick in EASE_TICKS - 1 downTo 0) {
                    val sample = rotationAt(rotations, tickNanos - tick * Nanos.PER_TICK)
                    lastEasedYaw = easedYaw
                    lastEasedPitch = easedPitch
                    easedPitch += (sample.b.toFloat() - easedPitch) * 0.5f
                    easedYaw += (sample.a.toFloat() - easedYaw) * 0.5f
                }
            }
            return HandState(handHeight, lastHandHeight, previousItem, swing, easedYaw, easedPitch, lastEasedYaw, lastEasedPitch)
        }

        private fun rotationAt(track: SampleTrack, nanos: Long) = run {
            val limit = nanos + Nanos.PER_TICK / 2
            var found = track[0]
            for (index in 0 until track.size) {
                val sample = track[index]
                if (sample.nanos > limit) break
                found = sample
            }
            found
        }
    }
}
