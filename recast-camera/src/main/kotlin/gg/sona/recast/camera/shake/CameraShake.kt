package gg.sona.recast.camera.shake

import gg.sona.recast.camera.Rotation
import gg.sona.recast.core.time.Nanos
import org.joml.Vector3d
import kotlin.math.cos
import kotlin.math.sin

class CameraShake(private val seed: Long = 0x5EED) {
    var continuousAmplitude: Double = 0.0

    var continuousFrequencyHz: Double = 1.6

    val active: List<ShakeImpulse>
        field = ArrayList<ShakeImpulse>()

    fun clear() = active.clear()

    fun offsetAt(nanos: Long): ShakeOffset {
        if (active.isEmpty() && continuousAmplitude <= 0.0) return ShakeOffset.NONE
        val position = Vector3d()
        var yaw = 0.0
        var pitch = 0.0
        var roll = 0.0
        if (continuousAmplitude > 0.0) {
            val seconds = nanos / Nanos.PER_SECOND.toDouble()
            val phase = seconds * continuousFrequencyHz * Math.PI * 2.0
            val amplitude = continuousAmplitude
            position.add(
                (sin(phase + noise(7)) + 0.5 * sin(phase * 2.3 + noise(8))) * amplitude * 0.02,
                (sin(phase * 1.17 + noise(9)) + 0.5 * sin(phase * 2.9 + noise(10))) * amplitude * 0.02,
                (cos(phase * 0.83 + noise(11))) * amplitude * 0.015,
            )
            yaw += (sin(phase * 0.93 + noise(12)) + 0.4 * sin(phase * 2.1 + noise(13))) * amplitude * 0.6
            pitch += (cos(phase * 1.07 + noise(14)) + 0.4 * sin(phase * 2.7 + noise(15))) * amplitude * 0.45
            roll += sin(phase * 0.61 + noise(16)) * amplitude * 0.25
        }
        for (impulse in active) {
            val amplitude = impulse.envelope(nanos)
            if (amplitude == 0.0) continue
            val seconds = (nanos - impulse.startNanos) / Nanos.PER_SECOND.toDouble()
            val phase = seconds * impulse.frequencyHz * Math.PI * 2.0
            position.add(
                sin(phase + noise(1)) * amplitude * 0.05,
                sin(phase * 1.3 + noise(2)) * amplitude * 0.05,
                cos(phase * 0.7 + noise(3)) * amplitude * 0.05,
            )
            yaw += sin(phase * 0.9 + noise(4)) * amplitude * impulse.rotational
            pitch += cos(phase * 1.1 + noise(5)) * amplitude * impulse.rotational
            roll += sin(phase * 0.5 + noise(6)) * amplitude * impulse.rotational * 0.5
        }
        return ShakeOffset(position, Rotation(yaw, pitch, roll))
    }

    private fun noise(salt: Int): Double {
        val mixed = (seed * 6364136223846793005L + salt * 1442695040888963407L)
        return ((mixed ushr 11).toDouble() / (1L shl 53)) * Math.PI * 2.0
    }
}

