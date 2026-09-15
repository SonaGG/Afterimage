package gg.sona.afterimage.mc.common


class ReplayClock {

    var partialTick: Float = 0f
        private set

    var active: Boolean = false
        private set

    var manualTick: Boolean = false

    val worldTickAllowed: Boolean get() = !active || manualTick

    fun apply(positionNanos: Long, lastTickNanos: Long = Math.floorDiv(positionNanos, TICK_NANOS) * TICK_NANOS): Float {
        active = true
        partialTick = ((positionNanos - lastTickNanos).toDouble() / TICK_NANOS).toFloat().coerceIn(0f, 1f)
        return partialTick
    }

    fun reset() {
        active = false
        manualTick = false
    }

    private companion object {
        const val TICK_NANOS = 50_000_000L
    }
}
