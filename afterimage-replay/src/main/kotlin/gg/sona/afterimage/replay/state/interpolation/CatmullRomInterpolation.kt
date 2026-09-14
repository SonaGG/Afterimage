package gg.sona.afterimage.replay.state.interpolation

import gg.sona.afterimage.core.spline.CatmullRom
import gg.sona.afterimage.replay.state.camera.MotionSample
import gg.sona.afterimage.replay.state.shadow.MotionHistory
import gg.sona.afterimage.replay.state.shadow.Pose
import kotlin.math.sqrt

object CatmullRomInterpolation : Interpolation {
    override fun poseAt(history: MotionHistory, nanos: Long): Pose? {
        val track = history.positions
        val count = track.size
        if (count < 4) return LinearInterpolation.poseAt(history, nanos)
        val index = Interpolation.bracket(track, nanos)
        if (index <= 0 || index + 2 >= count) return LinearInterpolation.poseAt(history, nanos)
        if (Interpolation.isIdleGap(track, index - 1) || Interpolation.isIdleGap(
                track,
                index
            ) || Interpolation.isIdleGap(track, index + 1)
        ) return LinearInterpolation.poseAt(history, nanos)
        val rotation = DoubleArray(3)
        if (!Interpolation.linear(history.rotations, nanos, true, rotation)) return null
        val p0 = track[index - 1]
        val p1 = track[index]
        val p2 = track[index + 1]
        val p3 = track[index + 2]
        val t = Interpolation.fraction(track, index, nanos)
        val weights = CatmullRom.knotSpacing(distance(p0, p1), distance(p1, p2), distance(p2, p3))
        return Pose(
            CatmullRom.evaluate(p0.a, p1.a, p2.a, p3.a, weights, t, ::lerp),
            CatmullRom.evaluate(p0.b, p1.b, p2.b, p3.b, weights, t, ::lerp),
            CatmullRom.evaluate(p0.c, p1.c, p2.c, p3.c, weights, t, ::lerp),
            rotation[0].toFloat(),
            rotation[1].toFloat(),
            rotation[2].toFloat(),
        )
    }

    private fun lerp(a: Double, b: Double, t: Double): Double = a + (b - a) * t

    private fun distance(a: MotionSample, b: MotionSample): Double {
        val dx = b.a - a.a
        val dy = b.b - a.b
        val dz = b.c - a.c
        return sqrt(dx * dx + dy * dy + dz * dz)
    }
}
