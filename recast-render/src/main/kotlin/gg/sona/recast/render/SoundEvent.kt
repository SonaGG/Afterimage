package gg.sona.recast.render


class SoundEvent(
    val outputNanos: Long,
    val clip: String,
    val x: Double,
    val y: Double,
    val z: Double,
    val volume: Float,
    val pitch: Float,
    val attenuate: Boolean,
    val rolloff: Float,
    val listenerX: Double,
    val listenerY: Double,
    val listenerZ: Double,
    val rightX: Double,
    val rightY: Double,
    val rightZ: Double,
)
