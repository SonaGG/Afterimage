package gg.sona.afterimage.camera.track.interpolator

object DoubleInterpolator : ValueInterpolator<Double> {
    override fun lerp(a: Double, b: Double, t: Double): Double = a + (b - a) * t

    override fun distance(a: Double, b: Double): Double = kotlin.math.abs(b - a)

    override fun bezier(p1: Double, control1: Double, control2: Double, p2: Double, t: Double): Double {
        val u = 1.0 - t
        return u * u * u * p1 + 3 * u * u * t * control1 + 3 * u * t * t * control2 + t * t * t * p2
    }

    override fun tangentControl(previous: Double, current: Double, next: Double, scale: Double): Double =
        current + (next - previous) * scale

    override fun translate(value: Double, from: Double, to: Double): Double = value + (to - from)
}
