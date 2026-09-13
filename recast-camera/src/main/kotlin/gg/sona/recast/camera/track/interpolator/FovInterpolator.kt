package gg.sona.recast.camera.track.interpolator

import kotlin.math.atan
import kotlin.math.tan

object FovInterpolator : ValueInterpolator<Double> {
    private fun toFocal(fovDegrees: Double): Double = 1.0 / tan(Math.toRadians(fovDegrees) / 2.0)
    private fun toFov(focal: Double): Double = Math.toDegrees(2.0 * atan(1.0 / focal))

    override fun lerp(a: Double, b: Double, t: Double): Double =
        toFov(DoubleInterpolator.lerp(toFocal(a), toFocal(b), t))

    override fun distance(a: Double, b: Double): Double = DoubleInterpolator.distance(toFocal(a), toFocal(b))

    override fun bezier(p1: Double, control1: Double, control2: Double, p2: Double, t: Double): Double =
        toFov(DoubleInterpolator.bezier(toFocal(p1), toFocal(control1), toFocal(control2), toFocal(p2), t))

    override fun tangentControl(previous: Double, current: Double, next: Double, scale: Double): Double =
        toFov(DoubleInterpolator.tangentControl(toFocal(previous), toFocal(current), toFocal(next), scale))

    override fun translate(value: Double, from: Double, to: Double): Double = value + (to - from)
}