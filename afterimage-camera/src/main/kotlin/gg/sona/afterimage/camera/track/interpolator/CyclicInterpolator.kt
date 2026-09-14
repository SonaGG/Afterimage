package gg.sona.afterimage.camera.track.interpolator

class CyclicInterpolator(private val period: Double) : ValueInterpolator<Double> {
    private fun forward(from: Double, to: Double): Double {
        if (to - from >= period - 1e-6) return from + period
        val delta = ((to - from) % period + period) % period
        return from + delta
    }

    private fun wrap(value: Double): Double = ((value % period) + period) % period

    override fun lerp(a: Double, b: Double, t: Double): Double = wrap(a + (forward(a, b) - a) * t)

    override fun distance(a: Double, b: Double): Double = forward(a, b) - a

    override fun bezier(p1: Double, control1: Double, control2: Double, p2: Double, t: Double): Double {
        val end = forward(p1, p2)
        val c1 = forward(p1, control1).coerceIn(p1, end)
        val c2 = forward(p1, control2).coerceIn(p1, end)
        return wrap(DoubleInterpolator.bezier(p1, c1, c2, end, t))
    }

    override fun tangentControl(previous: Double, current: Double, next: Double, scale: Double): Double =
        current + (forward(previous, next) - previous) * scale

    override fun translate(value: Double, from: Double, to: Double): Double = wrap(value + (to - from))
}
