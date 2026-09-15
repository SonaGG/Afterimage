package gg.sona.afterimage.world.interpolation

import gg.sona.afterimage.world.MotionHistory
import gg.sona.afterimage.world.Pose
import gg.sona.afterimage.world.SampleTrack

interface Interpolation {
    fun poseAt(history: MotionHistory, nanos: Long): Pose?

    companion object {
        const val IDLE_GAP_FACTOR = 4L

        fun lerpAngle(from: Float, to: Float, t: Float): Float {
            var delta = (to - from) % 360f
            if (delta > 180f) delta -= 360f
            if (delta < -180f) delta += 360f
            return from + delta * t
        }

        fun bracket(track: SampleTrack, nanos: Long): Int {
            val count = track.size
            if (count == 0) return -1
            var index = count - 1
            while (index > 0 && track[index].nanos > nanos) index--
            return index
        }

        fun isIdleGap(track: SampleTrack, index: Int): Boolean =
            track[index + 1].nanos - track[index].nanos > track.cadenceNanos * IDLE_GAP_FACTOR

        fun fraction(track: SampleTrack, index: Int, nanos: Long): Double {
            val from = track[index]
            val to = track[index + 1]
            if (to.nanos <= from.nanos) return 1.0
            val start = if (isIdleGap(track, index)) to.nanos - track.cadenceNanos else from.nanos
            if (nanos <= start) return 0.0
            return ((nanos - start).toDouble() / (to.nanos - start)).coerceIn(0.0, 1.0)
        }

        fun linear(track: SampleTrack, nanos: Long, angular: Boolean, into: DoubleArray): Boolean {
            val count = track.size
            if (count == 0) return false
            val index = bracket(track, nanos)
            val from = track[index]
            if (index + 1 >= count) {
                into[0] = from.a
                into[1] = from.b
                into[2] = from.c
                return true
            }
            val to = track[index + 1]
            val t = fraction(track, index, nanos)
            if (angular) {
                into[0] = lerpAngle(from.a.toFloat(), to.a.toFloat(), t.toFloat()).toDouble()
                into[1] = lerpAngle(from.b.toFloat(), to.b.toFloat(), t.toFloat()).toDouble()
                into[2] = lerpAngle(from.c.toFloat(), to.c.toFloat(), t.toFloat()).toDouble()
            } else {
                into[0] = from.a + (to.a - from.a) * t
                into[1] = from.b + (to.b - from.b) * t
                into[2] = from.c + (to.c - from.c) * t
            }
            return true
        }

        fun pose(position: DoubleArray, rotation: DoubleArray): Pose =
            Pose(
                position[0],
                position[1],
                position[2],
                rotation[0].toFloat(),
                rotation[1].toFloat(),
                rotation[2].toFloat()
            )
    }
}
