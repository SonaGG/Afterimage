package gg.sona.afterimage.core.spline

import kotlin.math.abs
import kotlin.math.pow

object CatmullRom {
    private const val ALPHA = 0.5
    private const val MIN_SPACING = 1e-4

    fun knotSpacing(d0: Double, d1: Double, d2: Double): DoubleArray = doubleArrayOf(
        d0.coerceAtLeast(MIN_SPACING).pow(ALPHA),
        d1.coerceAtLeast(MIN_SPACING).pow(ALPHA),
        d2.coerceAtLeast(MIN_SPACING).pow(ALPHA),
    )

    inline fun <T> evaluate(p0: T, p1: T, p2: T, p3: T, weights: DoubleArray, t: Double, lerp: (T, T, Double) -> T): T {
        val w0 = weights[0]
        val w1 = weights[1]
        val w2 = weights[2]
        val t0 = -w0
        val t1 = 0.0
        val t2 = w1
        val t3 = w1 + w2
        val u = t * w1
        val a1 = lerp(p0, p1, fraction(t0, t1, u))
        val a2 = lerp(p1, p2, fraction(t1, t2, u))
        val a3 = lerp(p2, p3, fraction(t2, t3, u))
        val b1 = lerp(a1, a2, fraction(t0, t2, u))
        val b2 = lerp(a2, a3, fraction(t1, t3, u))
        return lerp(b1, b2, fraction(t1, t2, u))
    }

    fun fraction(from: Double, to: Double, u: Double): Double {
        val span = to - from
        return if (abs(span) < 1e-9) 0.0 else (u - from) / span
    }
}
