package gg.sona.afterimage.editor

import kotlin.math.floor

enum class ValueLane(
    val kind: LaneKind,
    val label: String,
    val min: Double,
    val max: Double,
    val default: Double,
    val format: String
) {
    SPEED(LaneKind.SPEED, "Speed", 0.1, 8.0, 1.0, "%.2fx"),
    FOV(LaneKind.FOV, "FOV", 10.0, 150.0, 70.0, "%.0f°"),
    TIME_OF_DAY(LaneKind.TIME_OF_DAY, "Time of day", 0.0, 24000.0, 6000.0, "%.0f"),
    SHAKE(LaneKind.SHAKE, "Shake", 0.0, 5.0, 0.0, "%.2f"),
    FREEZE(LaneKind.FREEZE, "Freeze", 0.1, 60.0, 2.0, "%.1f s"),
    SHAKE_FREQUENCY(LaneKind.SHAKE_FREQUENCY, "Shake frequency", 0.1, 30.0, 1.6, "%.2f Hz"),
    FOCUS(LaneKind.FOCUS, "Focus", 0.5, 256.0, 8.0, "%.1f m");

    fun format(value: Double): String = when (this) {
        TIME_OF_DAY -> {
            val ticks = value.toLong().coerceIn(0L, 23999L)
            String.format("%02d:%02d", (ticks / 1000 + 6) % 24, (ticks % 1000) * 60 / 1000)
        }

        SPEED -> if (value == floor(value)) "${value.toInt()}x" else String.format("%.2fx", value)
        else -> String.format(format, value)
    }

    companion object {
        fun forKind(kind: LaneKind): ValueLane? = entries.firstOrNull { it.kind == kind }
    }
}
