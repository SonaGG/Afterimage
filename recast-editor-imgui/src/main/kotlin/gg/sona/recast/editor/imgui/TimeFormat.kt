package gg.sona.recast.editor.imgui

import gg.sona.recast.core.time.Nanos
import kotlin.math.abs
import kotlin.math.floor

object TimeFormat {
    fun clock(nanos: Long): String {
        val totalMillis = nanos / Nanos.PER_MILLI
        val minutes = totalMillis / 60_000
        val seconds = (totalMillis / 1000) % 60
        val millis = totalMillis % 1000
        return String.format("%02d:%02d.%03d", minutes, seconds, millis)
    }

    fun short(nanos: Long): String {
        val totalMillis = nanos / Nanos.PER_MILLI
        val minutes = totalMillis / 60_000
        val seconds = (totalMillis / 1000) % 60
        val tenths = (totalMillis % 1000) / 100
        return String.format("%d:%02d.%d", minutes, seconds, tenths)
    }

    fun timecode(nanos: Long, fps: Int): String {
        val totalMillis = nanos / Nanos.PER_MILLI
        val minutes = totalMillis / 60_000
        val seconds = (totalMillis / 1000) % 60
        val frame = ((nanos % Nanos.PER_SECOND) * fps) / Nanos.PER_SECOND
        return String.format("%02d:%02d:%02d", minutes, seconds, frame)
    }

    fun ticks(nanos: Long): Long = nanos / Nanos.PER_TICK

    fun speed(value: Double): String {
        val magnitude = abs(value)
        val text =
            if (magnitude >= 1.0 && magnitude == floor(magnitude)) {
                "${magnitude.toInt()}x"
            } else {
                String.format("%.1fx", magnitude)
            }
        return if (value < 0) "-$text" else text
    }

    fun parseClock(text: String): Long? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        val parts = trimmed.split(":")
        return try {
            when (parts.size) {
                1 -> (parts[0].toDouble() * Nanos.PER_SECOND).toLong()
                2 -> parts[0].toLong() * 60 * Nanos.PER_SECOND + (parts[1].toDouble() * Nanos.PER_SECOND).toLong()
                else -> null
            }
        } catch (_: NumberFormatException) {
            null
        }
    }
}
