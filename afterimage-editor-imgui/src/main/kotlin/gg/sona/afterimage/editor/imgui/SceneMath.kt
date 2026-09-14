package gg.sona.afterimage.editor.imgui

import org.joml.Vector3d
import kotlin.math.abs
import kotlin.math.sqrt

object SceneMath {
    fun axisOffset(ray: DoubleArray, point: Vector3d, axis: Vector3d): Double {
        val rx = ray[0] - point.x
        val ry = ray[1] - point.y
        val rz = ray[2] - point.z
        val b = ray[3] * axis.x + ray[4] * axis.y + ray[5] * axis.z
        val d = ray[3] * rx + ray[4] * ry + ray[5] * rz
        val e = axis.x * rx + axis.y * ry + axis.z * rz
        val denominator = 1.0 - b * b
        if (abs(denominator) < 1e-6) return 0.0
        return (e - b * d) / denominator
    }

    fun planePoint(ray: DoubleArray, point: Vector3d, normal: Vector3d): Vector3d? {
        val denominator = ray[3] * normal.x + ray[4] * normal.y + ray[5] * normal.z
        if (abs(denominator) < 1e-6) return null
        val t =
            ((point.x - ray[0]) * normal.x + (point.y - ray[1]) * normal.y + (point.z - ray[2]) * normal.z) / denominator
        if (t < 0.0) return null
        return Vector3d(ray[0] + ray[3] * t, ray[1] + ray[4] * t, ray[2] + ray[5] * t)
    }

    fun segmentDistance(px: Float, py: Float, x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x2 - x1
        val dy = y2 - y1
        val lengthSquared = dx * dx + dy * dy
        val t = if (lengthSquared <= 1e-6f) 0f else (((px - x1) * dx + (py - y1) * dy) / lengthSquared).coerceIn(0f, 1f)
        val cx = x1 + dx * t
        val cy = y1 + dy * t
        return sqrt(((px - cx) * (px - cx) + (py - cy) * (py - cy)).toDouble()).toFloat()
    }

    fun pointInPolygon(px: Float, py: Float, polygon: List<FloatArray>): Boolean {
        var inside = false
        var j = polygon.size - 1
        for (i in polygon.indices) {
            val xi = polygon[i][0]
            val yi = polygon[i][1]
            val xj = polygon[j][0]
            val yj = polygon[j][1]
            if ((yi > py) != (yj > py) && px < (xj - xi) * (py - yi) / (yj - yi + 1e-6f) + xi) inside = !inside
            j = i
        }
        return inside
    }

    fun convexHull(points: List<FloatArray>): List<FloatArray> {
        if (points.size < 3) return points
        val sorted = points.sortedWith(compareBy({ it[0] }, { it[1] }))
        val hull = ArrayList<FloatArray>(sorted.size * 2)
        for (point in sorted) {
            while (hull.size >= 2 && cross(
                    hull[hull.size - 2],
                    hull[hull.size - 1],
                    point
                ) <= 0f
            ) hull.removeAt(hull.size - 1)
            hull += point
        }
        val lower = hull.size + 1
        for (index in sorted.size - 2 downTo 0) {
            val point = sorted[index]
            while (hull.size >= lower && cross(hull[hull.size - 2], hull[hull.size - 1], point) <= 0f) hull.removeAt(
                hull.size - 1
            )
            hull += point
        }
        hull.removeAt(hull.size - 1)
        return hull
    }

    private fun cross(o: FloatArray, a: FloatArray, b: FloatArray): Float =
        (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0])

    fun wrapDegrees(value: Double): Double {
        var result = value
        while (result > 180.0) result -= 360.0
        while (result < -180.0) result += 360.0
        return result
    }
}
