package gg.sona.recast.camera

data class Easing(
    val kind: EasingKind,
    val x1: Double = kind.x1,
    val y1: Double = kind.y1,
    val x2: Double = kind.x2,
    val y2: Double = kind.y2,
) {
    fun apply(t: Double): Double =
        if (kind == EasingKind.CUSTOM) CubicBezier.solve(x1, y1, x2, y2, t) else kind.apply(t)

    fun clamped(t: Double): Double = apply(t.coerceIn(0.0, 1.0))

    val label: String get() = kind.label

    val isLinear: Boolean get() = kind == EasingKind.LINEAR || (kind == EasingKind.CUSTOM && x1 == y1 && x2 == y2)

    val isHold: Boolean get() = kind == EasingKind.HOLD

    val hasHandles: Boolean get() = kind == EasingKind.CUSTOM || kind.hasHandles

    fun slopeIn(): Double = slopeAt(0.0)

    fun slopeOut(): Double = slopeAt(1.0)

    private fun slopeAt(x: Double): Double = when {
        kind == EasingKind.HOLD -> 0.0
        hasHandles -> CubicBezier.slope(x1, y1, x2, y2, x)
        else -> {
            val h = 1e-4
            val a = apply((x - h).coerceIn(0.0, 1.0))
            val b = apply((x + h).coerceIn(0.0, 1.0))
            (b - a) / ((x + h).coerceIn(0.0, 1.0) - (x - h).coerceIn(0.0, 1.0))
        }
    }

    fun toCustom(): Easing =
        if (kind == EasingKind.CUSTOM) this
        else if (kind.hasHandles) Easing(EasingKind.CUSTOM, kind.x1, kind.y1, kind.x2, kind.y2)
        else LINEAR.toCustom()

    fun withOut(x1: Double, y1: Double): Easing {
        val base = toCustom()
        return base.copy(x1 = x1.coerceIn(0.0, 1.0), y1 = y1).simplified()
    }

    fun withIn(x2: Double, y2: Double): Easing {
        val base = toCustom()
        return base.copy(x2 = x2.coerceIn(0.0, 1.0), y2 = y2).simplified()
    }

    fun reversed(): Easing = when {
        kind == EasingKind.CUSTOM -> Easing(EasingKind.CUSTOM, 1.0 - x2, 1.0 - y2, 1.0 - x1, 1.0 - y1).simplified()
        kind.direction == EaseDirection.IN -> EasingKind.of(kind.family, EaseDirection.OUT)?.let { Easing(it) } ?: this
        kind.direction == EaseDirection.OUT -> EasingKind.of(kind.family, EaseDirection.IN)?.let { Easing(it) } ?: this
        else -> this
    }

    fun simplified(): Easing {
        if (kind != EasingKind.CUSTOM) return this
        for (preset in EasingKind.entries) {
            if (preset == EasingKind.CUSTOM || !preset.bezierExact) continue
            if (near(preset.x1, x1) && near(preset.y1, y1) && near(preset.x2, x2) && near(preset.y2, y2)) return Easing(
                preset
            )
        }
        return this
    }

    private fun near(a: Double, b: Double): Boolean = kotlin.math.abs(a - b) < 1e-4

    companion object {
        val LINEAR = Easing(EasingKind.LINEAR)
        val HOLD = Easing(EasingKind.HOLD)

        fun of(kind: EasingKind): Easing = Easing(kind)

        fun bezier(x1: Double, y1: Double, x2: Double, y2: Double): Easing =
            Easing(EasingKind.CUSTOM, x1.coerceIn(0.0, 1.0), y1, x2.coerceIn(0.0, 1.0), y2).simplified()

        fun legacy(ordinal: Int): Easing = Easing(EasingKind.LEGACY[ordinal.coerceIn(0, EasingKind.LEGACY.size - 1)])
    }
}
