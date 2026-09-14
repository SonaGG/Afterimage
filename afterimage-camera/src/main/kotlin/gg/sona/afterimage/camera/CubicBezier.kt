package gg.sona.afterimage.camera

import kotlin.math.abs

object CubicBezier {
    fun solve(x1: Double, y1: Double, x2: Double, y2: Double, x: Double): Double {
        if (x <= 0.0) return 0.0
        if (x >= 1.0) return 1.0
        val cx1 = x1.coerceIn(0.0, 1.0)
        val cx2 = x2.coerceIn(0.0, 1.0)
        if (cx1 == y1 && cx2 == y2) return x
        val t = parameterFor(cx1, cx2, x)
        return sample(y1, y2, t)
    }

    fun slope(x1: Double, y1: Double, x2: Double, y2: Double, x: Double): Double {
        val cx1 = x1.coerceIn(0.0, 1.0)
        val cx2 = x2.coerceIn(0.0, 1.0)
        val t = parameterFor(cx1, cx2, x.coerceIn(0.0, 1.0))
        val dx = derivative(cx1, cx2, t)
        val dy = derivative(y1, y2, t)
        return if (abs(dx) < 1e-9) if (abs(dy) < 1e-9) 1.0 else dy / 1e-9 else dy / dx
    }

    fun sample(a1: Double, a2: Double, t: Double): Double {
        val u = 1.0 - t
        return 3.0 * u * u * t * a1 + 3.0 * u * t * t * a2 + t * t * t
    }

    private fun derivative(a1: Double, a2: Double, t: Double): Double {
        val u = 1.0 - t
        return 3.0 * u * u * a1 + 6.0 * u * t * (a2 - a1) + 3.0 * t * t * (1.0 - a2)
    }

    private fun parameterFor(x1: Double, x2: Double, x: Double): Double {
        var t = x
        repeat(8) {
            val error = sample(x1, x2, t) - x
            if (abs(error) < 1e-7) return t
            val d = derivative(x1, x2, t)
            if (abs(d) < 1e-6) return bisect(x1, x2, x)
            t -= error / d
            if (t !in 0.0..1.0) return bisect(x1, x2, x)
        }
        return t
    }

    private fun bisect(x1: Double, x2: Double, x: Double): Double {
        var low = 0.0
        var high = 1.0
        var t = x
        repeat(40) {
            t = (low + high) / 2.0
            val value = sample(x1, x2, t)
            if (abs(value - x) < 1e-7) return t
            if (value < x) low = t else high = t
        }
        return t
    }
}
