package gg.sona.afterimage.mc.common

import org.joml.Matrix4f
import org.joml.Matrix4fc
import org.joml.Vector3d
import org.joml.Vector4f
import kotlin.math.abs

class ViewportProjection {
    private val projection = Matrix4f()
    private val modelView = Matrix4f()
    private val viewProjection = Matrix4f()
    private val inverse = Matrix4f()
    var originX = 0.0
        private set
    var originY = 0.0
        private set
    var originZ = 0.0
        private set
    var valid = false
        private set

    fun set(projection: Matrix4fc, modelView: Matrix4fc, originX: Double, originY: Double, originZ: Double) {
        this.projection.set(projection)
        this.modelView.set(modelView)
        this.projection.mul(this.modelView, viewProjection)
        viewProjection.invert(inverse)
        this.originX = originX
        this.originY = originY
        this.originZ = originZ
        valid = true
    }

    fun viewProjection(into: FloatArray): FloatArray = viewProjection.get(into)

    fun tanHalfFov(): FloatArray {
        if (!valid) return floatArrayOf(1f, 1f)
        val x = projection.m00()
        val y = projection.m11()
        return floatArrayOf(if (x != 0f) 1f / x else 1f, if (y != 0f) 1f / y else 1f)
    }

    fun ray(normalizedX: Float, normalizedY: Float): Vector3d? = worldRay(normalizedX, normalizedY)?.second

    fun worldRay(normalizedX: Float, normalizedY: Float): Pair<Vector3d, Vector3d>? {
        val (near, direction) = localRay(normalizedX, normalizedY) ?: return null
        return Vector3d(near).add(originX, originY, originZ) to direction
    }

    fun localRay(normalizedX: Float, normalizedY: Float): Pair<Vector3d, Vector3d>? {
        if (!valid) return null
        val ndcX = normalizedX * 2f - 1f
        val ndcY = 1f - normalizedY * 2f
        val first = unproject(ndcX, ndcY, 0.001f) ?: return null
        val second = unproject(ndcX, ndcY, 0.999f) ?: return null
        val forwardX = -modelView.m02().toDouble()
        val forwardY = -modelView.m12().toDouble()
        val forwardZ = -modelView.m22().toDouble()
        val firstDepth = first.x * forwardX + first.y * forwardY + first.z * forwardZ
        val secondDepth = second.x * forwardX + second.y * forwardY + second.z * forwardZ
        val near = if (firstDepth <= secondDepth) first else second
        val far = if (firstDepth <= secondDepth) second else first
        val direction = Vector3d(far).sub(near)
        if (direction.lengthSquared() < 1e-9) return null
        return near to direction.normalize()
    }

    fun project(x: Double, y: Double, z: Double): FloatArray? {
        if (!valid) return null
        val point = Vector4f((x - originX).toFloat(), (y - originY).toFloat(), (z - originZ).toFloat(), 1f)
        viewProjection.transform(point)
        if (point.w <= 1e-6f) return null
        return floatArrayOf((point.x / point.w + 1f) / 2f, (1f - point.y / point.w) / 2f, point.z / point.w)
    }

    fun screenBounds(minX: Double, minY: Double, minZ: Double, maxX: Double, maxY: Double, maxZ: Double): FloatArray {
        var left = Float.MAX_VALUE
        var top = Float.MAX_VALUE
        var right = -Float.MAX_VALUE
        var bottom = -Float.MAX_VALUE
        val corner = Vector4f()
        for (index in 0 until 8) {
            val x = (if (index and 1 == 0) minX else maxX) - originX
            val y = (if (index and 2 == 0) minY else maxY) - originY
            val z = (if (index and 4 == 0) minZ else maxZ) - originZ
            corner.set(x.toFloat(), y.toFloat(), z.toFloat(), 1f)
            viewProjection.transform(corner)
            if (corner.w <= 1e-6f) continue
            val sx = (corner.x / corner.w + 1f) / 2f
            val sy = (1f - corner.y / corner.w) / 2f
            if (sx < left) left = sx
            if (sy < top) top = sy
            if (sx > right) right = sx
            if (sy > bottom) bottom = sy
        }
        return floatArrayOf(left, top, right, bottom)
    }

    private fun unproject(x: Float, y: Float, z: Float): Vector3d? {
        val point = Vector4f(x, y, z, 1f)
        inverse.transform(point)
        if (Math.abs(point.w) < 1e-9f) return null
        return Vector3d((point.x / point.w).toDouble(), (point.y / point.w).toDouble(), (point.z / point.w).toDouble())
    }

    companion object {
        const val MAX_DISTANCE = 96.0

        fun intersect(origin: Vector3d, direction: Vector3d, minX: Double, minY: Double, minZ: Double, maxX: Double, maxY: Double, maxZ: Double): Double? {
            var tMin = 0.0
            var tMax = MAX_DISTANCE
            val origins = doubleArrayOf(origin.x, origin.y, origin.z)
            val directions = doubleArrayOf(direction.x, direction.y, direction.z)
            val mins = doubleArrayOf(minX, minY, minZ)
            val maxs = doubleArrayOf(maxX, maxY, maxZ)
            for (axis in 0 until 3) {
                val d = directions[axis]
                if (abs(d) < 1e-9) {
                    if (origins[axis] < mins[axis] || origins[axis] > maxs[axis]) return null
                    continue
                }
                var t1 = (mins[axis] - origins[axis]) / d
                var t2 = (maxs[axis] - origins[axis]) / d
                if (t1 > t2) {
                    val swap = t1
                    t1 = t2
                    t2 = swap
                }
                if (t1 > tMin) tMin = t1
                if (t2 < tMax) tMax = t2
                if (tMin > tMax) return null
            }
            return tMin
        }
    }
}
