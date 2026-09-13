package gg.sona.recast.camera.track.interpolator

import gg.sona.recast.camera.Rotation

object AngleInterpolator : ValueInterpolator<Double> {
    override fun lerp(a: Double, b: Double, t: Double): Double = Rotation.lerpAngle(a, b, t)

    override fun distance(a: Double, b: Double): Double = kotlin.math.abs(Rotation.shortestDelta(a, b))

    override fun bezier(p1: Double, control1: Double, control2: Double, p2: Double, t: Double): Double {
        val c1 = p1 + Rotation.shortestDelta(p1, control1)
        val c2 = c1 + Rotation.shortestDelta(c1, control2)
        val end = c2 + Rotation.shortestDelta(c2, p2)
        return DoubleInterpolator.bezier(p1, c1, c2, end, t)
    }

    override fun tangentControl(previous: Double, current: Double, next: Double, scale: Double): Double =
        current + Rotation.shortestDelta(previous, next) * scale

    override fun translate(value: Double, from: Double, to: Double): Double = value + Rotation.shortestDelta(from, to)
}
