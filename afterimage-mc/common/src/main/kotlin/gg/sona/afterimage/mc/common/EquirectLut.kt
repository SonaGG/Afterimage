package gg.sona.afterimage.mc.common

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan


class EquirectLut(private val width: Int, private val height: Int, private val faceSize: Int, faceFovDegrees: Float) {
    private val face = ByteArray(width * height)
    private val sample = FloatArray(width * height * 2)

    init {
        val forward = arrayOf(
            doubleArrayOf(0.0, 0.0, 1.0),
            doubleArrayOf(-1.0, 0.0, 0.0),
            doubleArrayOf(0.0, 0.0, -1.0),
            doubleArrayOf(1.0, 0.0, 0.0),
            doubleArrayOf(0.0, 1.0, 0.0),
            doubleArrayOf(0.0, -1.0, 0.0),
        )
        val right = arrayOf(
            doubleArrayOf(-1.0, 0.0, 0.0),
            doubleArrayOf(0.0, 0.0, -1.0),
            doubleArrayOf(1.0, 0.0, 0.0),
            doubleArrayOf(0.0, 0.0, 1.0),
            doubleArrayOf(-1.0, 0.0, 0.0),
            doubleArrayOf(-1.0, 0.0, 0.0),
        )
        val up = arrayOf(
            doubleArrayOf(0.0, 1.0, 0.0),
            doubleArrayOf(0.0, 1.0, 0.0),
            doubleArrayOf(0.0, 1.0, 0.0),
            doubleArrayOf(0.0, 1.0, 0.0),
            doubleArrayOf(0.0, 0.0, -1.0),
            doubleArrayOf(0.0, 0.0, 1.0),
        )
        val max = faceSize - 1.001f
        val scale = 1.0 / tan(Math.toRadians(faceFovDegrees / 2.0))
        for (y in 0 until height) {
            val latitude = (0.5 - (y + 0.5) / height) * Math.PI
            val cosLatitude = cos(latitude)
            val dy = sin(latitude)
            for (x in 0 until width) {
                val longitude = ((x + 0.5) / width - 0.5) * Math.PI * 2.0
                val dx = -sin(longitude) * cosLatitude
                val dz = cos(longitude) * cosLatitude
                var best = 0
                var bestDot = -2.0
                for (index in 0 until 6) {
                    val dot = dx * forward[index][0] + dy * forward[index][1] + dz * forward[index][2]
                    if (dot > bestDot) {
                        bestDot = dot
                        best = index
                    }
                }
                val a = (dx * right[best][0] + dy * right[best][1] + dz * right[best][2]) / bestDot * scale
                val b = (dx * up[best][0] + dy * up[best][1] + dz * up[best][2]) / bestDot * scale
                val pixel = y * width + x
                face[pixel] = best.toByte()
                sample[pixel * 2] = (((a + 1.0) / 2.0) * faceSize - 0.5).toFloat().coerceIn(0f, max)
                sample[pixel * 2 + 1] = (((1.0 - b) / 2.0) * faceSize - 0.5).toFloat().coerceIn(0f, max)
            }
        }
    }

    fun compose(faces: Array<ByteArray>, into: ByteArray) {
        val stride = faceSize * 4
        for (pixel in 0 until width * height) {
            val source = faces[face[pixel].toInt()]
            val sx = sample[pixel * 2]
            val sy = sample[pixel * 2 + 1]
            val x0 = sx.toInt()
            val y0 = sy.toInt()
            val fx = sx - x0
            val fy = sy - y0
            val x1 = minOf(x0 + 1, faceSize - 1)
            val y1 = minOf(y0 + 1, faceSize - 1)
            val out = pixel * 4
            for (channel in 0 until 4) {
                val c00 = source[y0 * stride + x0 * 4 + channel].toInt() and 0xFF
                val c10 = source[y0 * stride + x1 * 4 + channel].toInt() and 0xFF
                val c01 = source[y1 * stride + x0 * 4 + channel].toInt() and 0xFF
                val c11 = source[y1 * stride + x1 * 4 + channel].toInt() and 0xFF
                val top = c00 + (c10 - c00) * fx
                val bottom = c01 + (c11 - c01) * fx
                into[out + channel] = (top + (bottom - top) * fy + 0.5f).toInt().coerceIn(0, 255).toByte()
            }
        }
    }
}
