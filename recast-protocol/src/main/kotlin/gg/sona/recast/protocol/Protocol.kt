package gg.sona.recast.protocol

import kotlin.math.floor

object Protocol {
    const val VERSION = 47
    const val MINECRAFT_VERSION = "1.8.9"
    const val POSITION_SCALE = 32.0
    const val ANGLE_SCALE = 256.0 / 360.0

    fun toFixed(value: Double): Int = floor(value * POSITION_SCALE).toInt()

    fun fromFixed(value: Int): Double = value / POSITION_SCALE

    fun toAngle(degrees: Float): Byte = floor(degrees * ANGLE_SCALE).toInt().toByte()

    fun fromAngle(angle: Byte): Float = (angle.toInt() * 360) / 256f
}
