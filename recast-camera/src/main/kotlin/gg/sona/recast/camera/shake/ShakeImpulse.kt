package gg.sona.recast.camera.shake

data class ShakeImpulse(
    val startNanos: Long,
    val strength: Double,
    val durationNanos: Long,
    val frequencyHz: Double = 18.0,
    val rotational: Double = 1.0,
) {
    fun envelope(nanos: Long): Double {
        val elapsed = nanos - startNanos
        if (elapsed !in 0L..<durationNanos) return 0.0
        val t = elapsed.toDouble() / durationNanos
        return strength * (1.0 - t) * (1.0 - t)
    }
}
