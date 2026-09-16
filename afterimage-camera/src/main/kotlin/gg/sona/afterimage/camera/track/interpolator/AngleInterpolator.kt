package gg.sona.afterimage.camera.track.interpolator

import gg.sona.afterimage.camera.Rotation
import gg.sona.afterimage.core.spline.CatmullRom

object AngleInterpolator : ValueInterpolator<Double> {
    override fun lerp(a: Double, b: Double, t: Double): Double = Rotation.lerpAngle(a, b, t)

    override fun distance(a: Double, b: Double): Double = kotlin.math.abs(Rotation.shortestDelta(a, b))

    override fun bezier(p1: Double, control1: Double, control2: Double, p2: Double, t: Double): Double {
        val end = p1 + Rotation.shortestDelta(p1, p2)
        val c1 = p1 + Rotation.shortestDelta(p1, control1)
        val c2 = end + Rotation.shortestDelta(end, control2)
        return DoubleInterpolator.bezier(p1, c1, c2, end, t)
    }

    override fun catmullRom(p0: Double, p1: Double, p2: Double, p3: Double, weights: DoubleArray, t: Double): Double {
        val u0 = p1 + Rotation.shortestDelta(p1, p0)
        val u2 = p1 + Rotation.shortestDelta(p1, p2)
        val u3 = u2 + Rotation.shortestDelta(u2, p3)
        return CatmullRom.evaluate(u0, p1, u2, u3, weights, t, DoubleInterpolator::lerp)
    }

    override fun tangentControl(previous: Double, current: Double, next: Double, scale: Double): Double =
        current + Rotation.shortestDelta(previous, next) * scale

    override fun translate(value: Double, from: Double, to: Double): Double = value + Rotation.shortestDelta(from, to)
}
