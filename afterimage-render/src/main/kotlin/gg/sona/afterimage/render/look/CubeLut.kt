package gg.sona.afterimage.render.look

import java.nio.file.Files
import java.nio.file.Path

class CubeLut(val size: Int, val data: FloatArray, val title: String = "") {

    val entries: Int get() = size * size * size

    companion object {
        const val MAX_SIZE = 65
        private val WHITESPACE = Regex("[ \t]+")

        fun parse(text: String): CubeLut {
            var size = 0
            var oneDimensional = 0
            var title = ""
            var minimum = floatArrayOf(0f, 0f, 0f)
            var maximum = floatArrayOf(1f, 1f, 1f)
            val values = ArrayList<Float>()
            for (raw in text.lineSequence()) {
                val line = raw.substringBefore('#').trim()
                if (line.isEmpty()) continue
                val upper = line.uppercase()
                when {
                    upper.startsWith("TITLE") -> title = line.substringAfter(' ', "").trim().trim('"')
                    upper.startsWith("LUT_3D_SIZE") -> size = line.substringAfterLast(' ').trim().toInt()
                    upper.startsWith("LUT_1D_SIZE") -> oneDimensional = line.substringAfterLast(' ').trim().toInt()
                    upper.startsWith("DOMAIN_MIN") -> minimum = triple(line)
                    upper.startsWith("DOMAIN_MAX") -> maximum = triple(line)
                    upper.startsWith("LUT_3D_INPUT_RANGE") || upper.startsWith("LUT_1D_INPUT_RANGE") -> {
                        val parts = line.split(WHITESPACE).drop(1).map { it.toFloat() }
                        if (parts.size >= 2) {
                            minimum = floatArrayOf(parts[0], parts[0], parts[0])
                            maximum = floatArrayOf(parts[1], parts[1], parts[1])
                        }
                    }

                    line[0].isDigit() || line[0] == '-' || line[0] == '.' || line[0] == '+' -> {
                        for (part in line.split(WHITESPACE)) values += part.toFloat()
                    }
                }
            }
            if (size <= 0 && oneDimensional > 0) return fromOneDimensional(oneDimensional, values, title, minimum, maximum)
            require(size in 2..MAX_SIZE) { "LUT_3D_SIZE must be between 2 and $MAX_SIZE" }
            val expected = size * size * size * 3
            require(values.size >= expected) { "expected $expected values for a ${size}x${size}x$size LUT, found ${values.size}" }
            val data = FloatArray(expected)
            for (i in 0 until expected) {
                val channel = i % 3
                val range = maximum[channel] - minimum[channel]
                data[i] = if (range > 0f) ((values[i] - minimum[channel]) / range).coerceIn(0f, 1f) else values[i].coerceIn(0f, 1f)
            }
            return CubeLut(size, data, title)
        }

        fun load(path: Path): CubeLut = parse(Files.readString(path))

        private fun triple(line: String): FloatArray {
            val parts = line.split(WHITESPACE).drop(1).map { it.toFloat() }
            require(parts.size >= 3) { "expected three values in '$line'" }
            return floatArrayOf(parts[0], parts[1], parts[2])
        }

        private fun fromOneDimensional(
            points: Int,
            values: List<Float>,
            title: String,
            minimum: FloatArray,
            maximum: FloatArray,
        ): CubeLut {
            require(points >= 2 && values.size >= points * 3) { "expected ${points * 3} values for a 1D LUT" }
            val size = points.coerceAtMost(MAX_SIZE)
            val data = FloatArray(size * size * size * 3)
            fun curve(channel: Int, t: Float): Float {
                val position = t * (points - 1)
                val index = position.toInt().coerceIn(0, points - 2)
                val fraction = position - index
                val a = values[index * 3 + channel]
                val b = values[(index + 1) * 3 + channel]
                val raw = a + (b - a) * fraction
                val range = maximum[channel] - minimum[channel]
                return if (range > 0f) ((raw - minimum[channel]) / range).coerceIn(0f, 1f) else raw.coerceIn(0f, 1f)
            }
            var offset = 0
            for (b in 0 until size) for (g in 0 until size) for (r in 0 until size) {
                data[offset++] = curve(0, r / (size - 1f))
                data[offset++] = curve(1, g / (size - 1f))
                data[offset++] = curve(2, b / (size - 1f))
            }
            return CubeLut(size, data, title)
        }
    }
}
