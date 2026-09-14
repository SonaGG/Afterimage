package gg.sona.afterimage.camera

import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

enum class EasingKind(
    val family: EasingFamily,
    val direction: EaseDirection,
    val x1: Double,
    val y1: Double,
    val x2: Double,
    val y2: Double,
    val bezierExact: Boolean = true,
) {
    LINEAR(EasingFamily.LINEAR, EaseDirection.NONE, 1.0 / 3.0, 1.0 / 3.0, 2.0 / 3.0, 2.0 / 3.0) {
        override fun apply(t: Double): Double = t
    },
    EASE(EasingFamily.EASE, EaseDirection.IN_OUT, 1.0 / 3.0, 0.0, 2.0 / 3.0, 1.0) {
        override fun apply(t: Double): Double = CubicBezier.solve(x1, y1, x2, y2, t)
    },
    EASE_IN(EasingFamily.EASE, EaseDirection.IN, 1.0 / 3.0, 0.0, 2.0 / 3.0, 2.0 / 3.0) {
        override fun apply(t: Double): Double = CubicBezier.solve(x1, y1, x2, y2, t)
    },
    EASE_OUT(EasingFamily.EASE, EaseDirection.OUT, 1.0 / 3.0, 1.0 / 3.0, 2.0 / 3.0, 1.0) {
        override fun apply(t: Double): Double = CubicBezier.solve(x1, y1, x2, y2, t)
    },

    SINE_IN(EasingFamily.SINE, EaseDirection.IN, 0.12, 0.0, 0.39, 0.0, false) {
        override fun apply(t: Double): Double = 1.0 - cos(t * Math.PI / 2.0)
    },
    SINE_OUT(EasingFamily.SINE, EaseDirection.OUT, 0.61, 1.0, 0.88, 1.0, false) {
        override fun apply(t: Double): Double = sin(t * Math.PI / 2.0)
    },
    SINE_IN_OUT(EasingFamily.SINE, EaseDirection.IN_OUT, 0.37, 0.0, 0.63, 1.0, false) {
        override fun apply(t: Double): Double = -(cos(Math.PI * t) - 1.0) / 2.0
    },

    QUAD_IN(EasingFamily.QUAD, EaseDirection.IN, 0.11, 0.0, 0.5, 0.0, false) {
        override fun apply(t: Double): Double = t * t
    },
    QUAD_OUT(EasingFamily.QUAD, EaseDirection.OUT, 0.5, 1.0, 0.89, 1.0, false) {
        override fun apply(t: Double): Double = 1.0 - (1.0 - t) * (1.0 - t)
    },
    QUAD_IN_OUT(EasingFamily.QUAD, EaseDirection.IN_OUT, 0.45, 0.0, 0.55, 1.0, false) {
        override fun apply(t: Double): Double = if (t < 0.5) 2.0 * t * t else 1.0 - (-2.0 * t + 2.0).pow(2.0) / 2.0
    },

    CUBIC_IN(EasingFamily.CUBIC, EaseDirection.IN, 0.32, 0.0, 0.67, 0.0, false) {
        override fun apply(t: Double): Double = t * t * t
    },
    CUBIC_OUT(EasingFamily.CUBIC, EaseDirection.OUT, 0.33, 1.0, 0.68, 1.0, false) {
        override fun apply(t: Double): Double = 1.0 - (1.0 - t).pow(3.0)
    },
    CUBIC_IN_OUT(EasingFamily.CUBIC, EaseDirection.IN_OUT, 0.65, 0.0, 0.35, 1.0, false) {
        override fun apply(t: Double): Double = if (t < 0.5) 4.0 * t * t * t else 1.0 - (-2.0 * t + 2.0).pow(3.0) / 2.0
    },

    QUART_IN(EasingFamily.QUART, EaseDirection.IN, 0.5, 0.0, 0.75, 0.0, false) {
        override fun apply(t: Double): Double = t * t * t * t
    },
    QUART_OUT(EasingFamily.QUART, EaseDirection.OUT, 0.25, 1.0, 0.5, 1.0, false) {
        override fun apply(t: Double): Double = 1.0 - (1.0 - t).pow(4.0)
    },
    QUART_IN_OUT(EasingFamily.QUART, EaseDirection.IN_OUT, 0.76, 0.0, 0.24, 1.0, false) {
        override fun apply(t: Double): Double =
            if (t < 0.5) 8.0 * t * t * t * t else 1.0 - (-2.0 * t + 2.0).pow(4.0) / 2.0
    },

    QUINT_IN(EasingFamily.QUINT, EaseDirection.IN, 0.64, 0.0, 0.78, 0.0, false) {
        override fun apply(t: Double): Double = t.pow(5.0)
    },
    QUINT_OUT(EasingFamily.QUINT, EaseDirection.OUT, 0.22, 1.0, 0.36, 1.0, false) {
        override fun apply(t: Double): Double = 1.0 - (1.0 - t).pow(5.0)
    },
    QUINT_IN_OUT(EasingFamily.QUINT, EaseDirection.IN_OUT, 0.83, 0.0, 0.17, 1.0, false) {
        override fun apply(t: Double): Double =
            if (t < 0.5) 16.0 * t.pow(5.0) else 1.0 - (-2.0 * t + 2.0).pow(5.0) / 2.0
    },

    EXPO_IN(EasingFamily.EXPO, EaseDirection.IN, 0.7, 0.0, 0.84, 0.0, false) {
        override fun apply(t: Double): Double = if (t <= 0.0) 0.0 else 2.0.pow(10.0 * t - 10.0)
    },
    EXPO_OUT(EasingFamily.EXPO, EaseDirection.OUT, 0.16, 1.0, 0.3, 1.0, false) {
        override fun apply(t: Double): Double = if (t >= 1.0) 1.0 else 1.0 - 2.0.pow(-10.0 * t)
    },
    EXPO_IN_OUT(EasingFamily.EXPO, EaseDirection.IN_OUT, 0.87, 0.0, 0.13, 1.0, false) {
        override fun apply(t: Double): Double = when {
            t <= 0.0 -> 0.0
            t >= 1.0 -> 1.0
            t < 0.5 -> 2.0.pow(20.0 * t - 10.0) / 2.0
            else -> (2.0 - 2.0.pow(-20.0 * t + 10.0)) / 2.0
        }
    },

    CIRC_IN(EasingFamily.CIRC, EaseDirection.IN, 0.55, 0.0, 1.0, 0.45, false) {
        override fun apply(t: Double): Double = 1.0 - sqrt(1.0 - t * t)
    },
    CIRC_OUT(EasingFamily.CIRC, EaseDirection.OUT, 0.0, 0.55, 0.45, 1.0, false) {
        override fun apply(t: Double): Double = sqrt(1.0 - (t - 1.0) * (t - 1.0))
    },
    CIRC_IN_OUT(EasingFamily.CIRC, EaseDirection.IN_OUT, 0.85, 0.0, 0.15, 1.0, false) {
        override fun apply(t: Double): Double =
            if (t < 0.5) (1.0 - sqrt(1.0 - (2.0 * t).pow(2.0))) / 2.0 else (sqrt(1.0 - (-2.0 * t + 2.0).pow(2.0)) + 1.0) / 2.0
    },

    BACK_IN(EasingFamily.BACK, EaseDirection.IN, 0.36, 0.0, 0.66, -0.56, false) {
        override fun apply(t: Double): Double = C3 * t * t * t - C1 * t * t
    },
    BACK_OUT(EasingFamily.BACK, EaseDirection.OUT, 0.34, 1.56, 0.64, 1.0, false) {
        override fun apply(t: Double): Double = 1.0 + C3 * (t - 1.0).pow(3.0) + C1 * (t - 1.0).pow(2.0)
    },
    BACK_IN_OUT(EasingFamily.BACK, EaseDirection.IN_OUT, 0.68, -0.6, 0.32, 1.6, false) {
        override fun apply(t: Double): Double =
            if (t < 0.5) ((2.0 * t).pow(2.0) * ((C2 + 1.0) * 2.0 * t - C2)) / 2.0
            else ((2.0 * t - 2.0).pow(2.0) * ((C2 + 1.0) * (t * 2.0 - 2.0) + C2) + 2.0) / 2.0
    },

    ELASTIC_IN(EasingFamily.ELASTIC, EaseDirection.IN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, false) {
        override fun apply(t: Double): Double = when {
            t <= 0.0 -> 0.0
            t >= 1.0 -> 1.0
            else -> -(2.0.pow(10.0 * t - 10.0)) * sin((t * 10.0 - 10.75) * C4)
        }
    },
    ELASTIC_OUT(EasingFamily.ELASTIC, EaseDirection.OUT, Double.NaN, Double.NaN, Double.NaN, Double.NaN, false) {
        override fun apply(t: Double): Double = when {
            t <= 0.0 -> 0.0
            t >= 1.0 -> 1.0
            else -> 2.0.pow(-10.0 * t) * sin((t * 10.0 - 0.75) * C4) + 1.0
        }
    },
    ELASTIC_IN_OUT(EasingFamily.ELASTIC, EaseDirection.IN_OUT, Double.NaN, Double.NaN, Double.NaN, Double.NaN, false) {
        override fun apply(t: Double): Double = when {
            t <= 0.0 -> 0.0
            t >= 1.0 -> 1.0
            t < 0.5 -> -(2.0.pow(20.0 * t - 10.0) * sin((20.0 * t - 11.125) * C5)) / 2.0
            else -> (2.0.pow(-20.0 * t + 10.0) * sin((20.0 * t - 11.125) * C5)) / 2.0 + 1.0
        }
    },

    BOUNCE_IN(EasingFamily.BOUNCE, EaseDirection.IN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, false) {
        override fun apply(t: Double): Double = 1.0 - bounceOut(1.0 - t)
    },
    BOUNCE_OUT(EasingFamily.BOUNCE, EaseDirection.OUT, Double.NaN, Double.NaN, Double.NaN, Double.NaN, false) {
        override fun apply(t: Double): Double = bounceOut(t)
    },
    BOUNCE_IN_OUT(EasingFamily.BOUNCE, EaseDirection.IN_OUT, Double.NaN, Double.NaN, Double.NaN, Double.NaN, false) {
        override fun apply(t: Double): Double =
            if (t < 0.5) (1.0 - bounceOut(1.0 - 2.0 * t)) / 2.0 else (1.0 + bounceOut(2.0 * t - 1.0)) / 2.0
    },

    HOLD(EasingFamily.HOLD, EaseDirection.NONE, Double.NaN, Double.NaN, Double.NaN, Double.NaN, false) {
        override fun apply(t: Double): Double = 0.0
    },

    CUSTOM(EasingFamily.CUSTOM, EaseDirection.NONE, 1.0 / 3.0, 0.0, 2.0 / 3.0, 1.0) {
        override fun apply(t: Double): Double = CubicBezier.solve(x1, y1, x2, y2, t)
    };

    abstract fun apply(t: Double): Double

    val hasHandles: Boolean get() = !x1.isNaN()

    val label: String
        get() = when (family) {
            EasingFamily.LINEAR, EasingFamily.HOLD, EasingFamily.CUSTOM -> family.label
            EasingFamily.EASE -> when (direction) {
                EaseDirection.IN_OUT -> "Easy ease"
                EaseDirection.IN -> "Easy ease in"
                EaseDirection.OUT -> "Easy ease out"
                EaseDirection.NONE -> family.label
            }

            else -> "${family.label} ${direction.label.lowercase()}"
        }

    companion object {
        private const val C1 = 1.70158
        private const val C2 = C1 * 1.525
        private const val C3 = C1 + 1.0
        private const val C4 = 2.0 * Math.PI / 3.0
        private const val C5 = 2.0 * Math.PI / 4.5

        private fun bounceOut(t: Double): Double {
            val n1 = 7.5625
            val d1 = 2.75
            return when {
                t < 1.0 / d1 -> n1 * t * t
                t < 2.0 / d1 -> {
                    val u = t - 1.5 / d1; n1 * u * u + 0.75
                }

                t < 2.5 / d1 -> {
                    val u = t - 2.25 / d1; n1 * u * u + 0.9375
                }

                else -> {
                    val u = t - 2.625 / d1; n1 * u * u + 0.984375
                }
            }
        }

        val LEGACY: List<EasingKind> =
            listOf(LINEAR, QUAD_IN, QUAD_OUT, QUAD_IN_OUT, CUBIC_IN, CUBIC_OUT, CUBIC_IN_OUT, SINE_IN_OUT, HOLD)

        fun of(family: EasingFamily, direction: EaseDirection): EasingKind? =
            entries.firstOrNull { it.family == family && it.direction == direction }
    }
}
