package gg.sona.afterimage.replay.state.shadow

class ShadowEffect(
    val effectId: Int,
    var amplifier: Int,
    var durationTicks: Int,
    var hideParticles: Boolean,
    var appliedAtNanos: Long
) {
    fun remainingTicks(nowNanos: Long): Int {
        val elapsed = ((nowNanos - appliedAtNanos) / 50_000_000L).toInt()
        return durationTicks - elapsed
    }
}
