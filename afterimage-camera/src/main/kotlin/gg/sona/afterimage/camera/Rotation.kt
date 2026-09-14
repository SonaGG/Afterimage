package gg.sona.afterimage.camera

import org.joml.Vector3d
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class Rotation(val yaw: Double, val pitch: Double, val roll: Double = 0.0) {
    fun forward(): Vector3d {
        val yawRadians = Math.toRadians(yaw)
        val pitchRadians = Math.toRadians(pitch)
        val cosPitch = cos(pitchRadians)
        return Vector3d(-sin(yawRadians) * cosPitch, -sin(pitchRadians), cos(yawRadians) * cosPitch)
    }

    fun right(): Vector3d {
        val yawRadians = Math.toRadians(yaw)
        return Vector3d(-cos(yawRadians), 0.0, -sin(yawRadians))
    }

    companion object {
        val ZERO = Rotation(0.0, 0.0, 0.0)

        fun lookingAt(from: Vector3d, to: Vector3d, roll: Double = 0.0): Rotation {
            val dx = to.x - from.x
            val dy = to.y - from.y
            val dz = to.z - from.z
            val horizontal = sqrt(dx * dx + dz * dz)
            val yaw = Math.toDegrees(atan2(-dx, dz))
            val pitch = Math.toDegrees(-atan2(dy, horizontal))
            return Rotation(yaw, pitch, roll)
        }

        fun shortestDelta(from: Double, to: Double): Double {
            var delta = (to - from) % 360.0
            if (delta > 180.0) delta -= 360.0
            if (delta < -180.0) delta += 360.0
            return delta
        }

        fun lerpAngle(from: Double, to: Double, t: Double): Double = from + shortestDelta(from, to) * t

        fun rotateYaw(vector: Vector3d, yawDeltaDegrees: Double): Vector3d {
            val radians = Math.toRadians(yawDeltaDegrees)
            val cosDelta = cos(radians)
            val sinDelta = sin(radians)
            return Vector3d(
                vector.x * cosDelta - vector.z * sinDelta,
                vector.y,
                vector.x * sinDelta + vector.z * cosDelta
            )
        }
    }
}
