package gg.sona.afterimage.mc

import net.minecraft.client.TickTimer

class ReplayClock {

    var partialTick: Float = 0f
        private set

    var active: Boolean = false
        private set

    var manualTick: Boolean = false

    val worldTickAllowed: Boolean get() = !active || manualTick

    fun apply(
        timer: TickTimer,
        positionNanos: Long,
        lastTickNanos: Long = Math.floorDiv(positionNanos, TICK_NANOS) * TICK_NANOS
    ) {
        active = true
        partialTick = ((positionNanos - lastTickNanos).toDouble() / TICK_NANOS).toFloat().coerceIn(0f, 1f)
        timer.partialTick = partialTick
        timer.tickDelta = partialTick
    }

    fun reset() {
        active = false
        manualTick = false
    }

    private companion object {
        const val TICK_NANOS = 50_000_000L
    }
}
