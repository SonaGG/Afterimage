package gg.sona.recast.editor.imgui

import gg.sona.recast.camera.CameraPose
import gg.sona.recast.camera.Rotation
import gg.sona.recast.editor.host.GizmoBatch
import org.joml.Vector3d
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

// TODO: move this somewhere else one day
class GizmoDraw(val batch: GizmoBatch) {
    var eye = Vector3d()
    var layer = GizmoBatch.WORLD
    var light: Vector3d = Vector3d(0.35, 0.8, 0.45).normalize()

    var pixelScale = 0.0

    fun line(a: Vector3d, b: Vector3d, color: Int, width: Float = 2f, layer: Int = this.layer) {
        if (width <= HAIRLINE_WIDTH || pixelScale <= 0.0) {
            batch.line(a.x, a.y, a.z, b.x, b.y, b.z, color, width, layer)
        } else {
            polyline(listOf(a, b), color, width, layer)
        }
    }

    fun polyline(
        points: List<Vector3d>,
        color: Int,
        width: Float = 2f,
        layer: Int = this.layer,
        closed: Boolean = false,
        depthOffset: Double = 0.0
    ) {
        val count = points.size
        if (count < 2) return
        if (width <= HAIRLINE_WIDTH || pixelScale <= 0.0) {
            for (index in 1 until count) {
                val a = points[index - 1]
                val b = points[index]
                batch.line(a.x, a.y, a.z, b.x, b.y, b.z, color, width, layer)
            }
            if (closed) {
                val a = points[count - 1]
                val b = points[0]
                batch.line(a.x, a.y, a.z, b.x, b.y, b.z, color, width, layer)
            }
            return
        }
        val segments = if (closed) count else count - 1
        val tangents = arrayOfNulls<Vector3d>(segments)
        var lastTangent: Vector3d? = null
        for (index in 0 until segments) {
            val tangent = Vector3d(points[(index + 1) % count]).sub(points[index])
            val length = tangent.length()
            if (length > 1e-9) {
                tangent.mul(1.0 / length)
                lastTangent = tangent
            }
            tangents[index] = if (length > 1e-9) tangent else lastTangent
        }
        if (lastTangent == null) return
        for (index in 0 until segments) if (tangents[index] == null) tangents[index] = lastTangent
        val left = arrayOfNulls<Vector3d>(count)
        val right = arrayOfNulls<Vector3d>(count)
        for (index in 0 until count) {
            val point = points[index]
            val previous = tangents[if (closed) (index - 1 + segments) % segments else maxOf(index - 1, 0)]!!
            val next = tangents[if (closed) index % segments else minOf(index, segments - 1)]!!
            val toEye = Vector3d(eye).sub(point)
            val distance = toEye.length()
            val half = (width * 0.5 * pixelScale * distance).coerceAtLeast(MIN_HALF_WIDTH)
            val sidePrevious = side(previous, toEye)
            val sideNext = side(next, toEye)
            val miter = Vector3d(sidePrevious).add(sideNext)
            var scale = 1.0
            if (miter.lengthSquared() < 1e-10) {
                miter.set(sideNext)
            } else {
                miter.normalize()
                scale = (1.0 / miter.dot(sideNext).coerceAtLeast(1e-3)).coerceIn(1.0, MITER_LIMIT)
            }
            val center = Vector3d(point)
            if (depthOffset != 0.0) center.sub(Vector3d(toEye).mul(depthOffset))
            if (!closed && index == 0) center.sub(Vector3d(next).mul(half))
            if (!closed && index == count - 1) center.add(Vector3d(previous).mul(half))
            val offset = miter.mul(half * scale)
            left[index] = Vector3d(center).sub(offset)
            right[index] = Vector3d(center).add(offset)
        }
        for (index in 0 until segments) {
            val b = (index + 1) % count
            val l0 = left[index]!!
            val r0 = right[index]!!
            val l1 = left[b]!!
            val r1 = right[b]!!
            batch.triangle(l0.x, l0.y, l0.z, r0.x, r0.y, r0.z, r1.x, r1.y, r1.z, color, layer)
            batch.triangle(l0.x, l0.y, l0.z, r1.x, r1.y, r1.z, l1.x, l1.y, l1.z, color, layer)
        }
    }

    private fun side(tangent: Vector3d, toEye: Vector3d): Vector3d {
        val side = Vector3d(tangent).cross(toEye)
        if (side.lengthSquared() < 1e-12) return basis(tangent).first
        return side.normalize()
    }

    fun triangle(a: Vector3d, b: Vector3d, c: Vector3d, color: Int, shaded: Boolean = true, layer: Int = this.layer) {
        val tint = if (shaded) shade(color, normal(a, b, c)) else color
        batch.triangle(a.x, a.y, a.z, b.x, b.y, b.z, c.x, c.y, c.z, tint, layer)
    }

    fun quad(
        a: Vector3d,
        b: Vector3d,
        c: Vector3d,
        d: Vector3d,
        color: Int,
        shaded: Boolean = true,
        layer: Int = this.layer
    ) {
        triangle(a, b, c, color, shaded, layer)
        triangle(a, c, d, color, shaded, layer)
    }

    fun cone(
        base: Vector3d,
        tip: Vector3d,
        u: Vector3d,
        v: Vector3d,
        radius: Double,
        color: Int,
        sides: Int,
        layer: Int = this.layer
    ) {
        var previous = ringPoint(base, u, v, radius, 0.0)
        for (step in 1..sides) {
            val angle = step * TAU / sides
            val current = ringPoint(base, u, v, radius, angle)
            triangle(previous, current, tip, color, true, layer)
            triangle(current, previous, base, color, true, layer)
            previous = current
        }
    }

    fun circle(
        center: Vector3d,
        normal: Vector3d,
        radius: Double,
        color: Int,
        width: Float,
        segments: Int,
        layer: Int = this.layer
    ) {
        val axis = Vector3d(normal).normalize()
        val (u, v) = basis(axis)
        val points = ArrayList<Vector3d>(segments)
        for (step in 0 until segments) points += ringPoint(center, u, v, radius, step * TAU / segments)
        polyline(points, color, width, layer, closed = true)
    }

    fun fan(
        center: Vector3d,
        u: Vector3d,
        v: Vector3d,
        radius: Double,
        fromAngle: Double,
        toAngle: Double,
        color: Int,
        layer: Int = this.layer
    ) {
        val sweep = toAngle - fromAngle
        if (abs(sweep) < 1e-6) return
        val steps = (abs(sweep) / Math.toRadians(4.0)).toInt().coerceIn(1, 360)
        var previous = ringPoint(center, u, v, radius, fromAngle)
        for (step in 1..steps) {
            val current = ringPoint(center, u, v, radius, fromAngle + sweep * step / steps)
            batch.triangle(
                center.x,
                center.y,
                center.z,
                previous.x,
                previous.y,
                previous.z,
                current.x,
                current.y,
                current.z,
                color,
                layer
            )
            previous = current
        }
    }

    fun cube(
        center: Vector3d,
        half: Double,
        color: Int,
        u: Vector3d = X,
        v: Vector3d = Y,
        w: Vector3d = Z,
        layer: Int = this.layer
    ) {
        val corners = Array(8) { index ->
            Vector3d(center)
                .add(Vector3d(u).mul(if (index and 1 == 0) -half else half))
                .add(Vector3d(v).mul(if (index and 2 == 0) -half else half))
                .add(Vector3d(w).mul(if (index and 4 == 0) -half else half))
        }
        quad(corners[0], corners[2], corners[3], corners[1], color, true, layer)
        quad(corners[4], corners[5], corners[7], corners[6], color, true, layer)
        quad(corners[0], corners[1], corners[5], corners[4], color, true, layer)
        quad(corners[2], corners[6], corners[7], corners[3], color, true, layer)
        quad(corners[0], corners[4], corners[6], corners[2], color, true, layer)
        quad(corners[1], corners[3], corners[7], corners[5], color, true, layer)
    }

    fun cornerBox(
        min: Vector3d,
        max: Vector3d,
        color: Int,
        width: Float,
        fraction: Double = 0.22,
        layer: Int = this.layer
    ) {
        val size = Vector3d(max).sub(min)
        val lx = size.x * fraction
        val ly = size.y * fraction
        val lz = size.z * fraction
        for (index in 0 until 8) {
            val cx = if (index and 1 == 0) min.x else max.x
            val cy = if (index and 2 == 0) min.y else max.y
            val cz = if (index and 4 == 0) min.z else max.z
            val corner = Vector3d(cx, cy, cz)
            line(corner, Vector3d(cx + if (index and 1 == 0) lx else -lx, cy, cz), color, width, layer)
            line(corner, Vector3d(cx, cy + if (index and 2 == 0) ly else -ly, cz), color, width, layer)
            line(corner, Vector3d(cx, cy, cz + if (index and 4 == 0) lz else -lz), color, width, layer)
        }
    }

    fun frustum(pose: CameraPose, depth: Double, aspect: Double, color: Int, width: Float, layer: Int = this.layer) {
        val (forward, right, up) = poseBasis(pose.rotation)
        val halfHeight = depth * tan(Math.toRadians(pose.fov / 2.0))
        val halfWidth = halfHeight * aspect
        val center = Vector3d(pose.position).add(Vector3d(forward).mul(depth))
        val corners = listOf(
            Vector3d(center).add(Vector3d(right).mul(-halfWidth)).add(Vector3d(up).mul(halfHeight)),
            Vector3d(center).add(Vector3d(right).mul(halfWidth)).add(Vector3d(up).mul(halfHeight)),
            Vector3d(center).add(Vector3d(right).mul(halfWidth)).add(Vector3d(up).mul(-halfHeight)),
            Vector3d(center).add(Vector3d(right).mul(-halfWidth)).add(Vector3d(up).mul(-halfHeight)),
        )
        polyline(corners, color, width, layer, closed = true)
        for (corner in corners) line(pose.position, corner, color, width * 0.85f, layer)
        val peak = Vector3d(center).add(Vector3d(up).mul(halfHeight * 1.35))
        polyline(listOf(corners[0], peak, corners[1]), color, width, layer)
    }

    fun cameraBody(pose: CameraPose, size: Double, color: Int, layer: Int = this.layer) {
        val (forward, right, up) = poseBasis(pose.rotation)
        val back = Vector3d(pose.position).sub(Vector3d(forward).mul(size * 0.9))
        cube(Vector3d(back), size * 0.42, color, right, up, forward, layer)
        cone(
            Vector3d(pose.position).add(Vector3d(forward).mul(size * 0.2)),
            Vector3d(pose.position).sub(Vector3d(forward).mul(size * 0.5)),
            right,
            up,
            size * 0.42,
            color,
            10,
            layer
        )
    }

    fun billboard(center: Vector3d, radius: Double, color: Int, segments: Int = 20, layer: Int = this.layer) {
        val toEye = Vector3d(eye).sub(center)
        if (toEye.lengthSquared() < 1e-9) return
        toEye.normalize()
        val (u, v) = basis(toEye)
        var previous = ringPoint(center, u, v, radius, 0.0)
        for (step in 1..segments) {
            val current = ringPoint(center, u, v, radius, step * TAU / segments)
            batch.triangle(
                center.x,
                center.y,
                center.z,
                previous.x,
                previous.y,
                previous.z,
                current.x,
                current.y,
                current.z,
                color,
                layer
            )
            previous = current
        }
    }

    fun billboardRing(
        center: Vector3d,
        radius: Double,
        color: Int,
        width: Float,
        segments: Int = 28,
        layer: Int = this.layer
    ) {
        val toEye = Vector3d(eye).sub(center)
        if (toEye.lengthSquared() < 1e-9) return
        circle(center, toEye, radius, color, width, segments, layer)
    }

    fun billboardSquare(
        center: Vector3d,
        half: Double,
        fill: Int,
        outline: Int,
        width: Float,
        layer: Int = this.layer
    ) {
        val toEye = Vector3d(eye).sub(center)
        if (toEye.lengthSquared() < 1e-9) return
        toEye.normalize()
        val (u, v) = basis(toEye)
        val a = Vector3d(center).sub(Vector3d(u).mul(half)).sub(Vector3d(v).mul(half))
        val b = Vector3d(center).add(Vector3d(u).mul(half)).sub(Vector3d(v).mul(half))
        val c = Vector3d(center).add(Vector3d(u).mul(half)).add(Vector3d(v).mul(half))
        val d = Vector3d(center).sub(Vector3d(u).mul(half)).add(Vector3d(v).mul(half))
        if (fill ushr 24 != 0) quad(a, b, c, d, fill, false, layer)
        polyline(listOf(a, b, c, d), outline, width, layer, closed = true)
    }

    fun diamond(center: Vector3d, radius: Double, color: Int, layer: Int = this.layer) {
        val toEye = Vector3d(eye).sub(center)
        if (toEye.lengthSquared() < 1e-9) return
        toEye.normalize()
        val (u, v) = basis(toEye)
        val up = Vector3d(v)
        val a = Vector3d(center).add(Vector3d(up).mul(radius))
        val b = Vector3d(center).add(Vector3d(u).mul(radius))
        val c = Vector3d(center).sub(Vector3d(up).mul(radius))
        val d = Vector3d(center).sub(Vector3d(u).mul(radius))
        batch.triangle(a.x, a.y, a.z, b.x, b.y, b.z, c.x, c.y, c.z, color, layer)
        batch.triangle(a.x, a.y, a.z, c.x, c.y, c.z, d.x, d.y, d.z, color, layer)
    }

    fun shade(color: Int, normal: Vector3d): Int {
        val facing = normal.dot(light)
        val factor = (0.62 + 0.38 * ((facing + 1.0) / 2.0)).coerceIn(0.5, 1.0)
        val r = ((color and 0xFF) * factor).toInt().coerceIn(0, 255)
        val g = (((color shr 8) and 0xFF) * factor).toInt().coerceIn(0, 255)
        val b = (((color shr 16) and 0xFF) * factor).toInt().coerceIn(0, 255)
        return (color and 0xFF000000.toInt()) or (b shl 16) or (g shl 8) or r
    }

    private fun normal(a: Vector3d, b: Vector3d, c: Vector3d): Vector3d {
        val ab = Vector3d(b).sub(a)
        val ac = Vector3d(c).sub(a)
        val n = ab.cross(ac)
        val toEye = Vector3d(eye).sub(a)
        if (n.dot(toEye) < 0.0) n.negate()
        if (n.lengthSquared() < 1e-12) return Vector3d(0.0, 1.0, 0.0)
        return n.normalize()
    }

    companion object {
        const val TAU = Math.PI * 2.0
        const val HEAD_SIDES = 14
        const val HAIRLINE_WIDTH = 1.5f
        const val MITER_LIMIT = 2.5
        const val MIN_HALF_WIDTH = 1e-4
        const val RING_SEGMENTS = 72
        val X = Vector3d(1.0, 0.0, 0.0)
        val Y = Vector3d(0.0, 1.0, 0.0)
        val Z = Vector3d(0.0, 0.0, 1.0)
        val BOX_EDGES =
            listOf(0 to 1, 2 to 3, 4 to 5, 6 to 7, 0 to 2, 1 to 3, 4 to 6, 5 to 7, 0 to 4, 1 to 5, 2 to 6, 3 to 7)

        fun basis(axis: Vector3d): Pair<Vector3d, Vector3d> {
            val helper = if (abs(axis.y) < 0.9) Vector3d(0.0, 1.0, 0.0) else Vector3d(1.0, 0.0, 0.0)
            val u = Vector3d(helper).cross(axis).normalize()
            val v = Vector3d(axis).cross(u).normalize()
            return u to v
        }

        fun ringPoint(center: Vector3d, u: Vector3d, v: Vector3d, radius: Double, angle: Double): Vector3d =
            Vector3d(center).add(Vector3d(u).mul(cos(angle) * radius)).add(Vector3d(v).mul(sin(angle) * radius))

        fun ringPoints(
            center: Vector3d,
            axis: Vector3d,
            radius: Double,
            eye: Vector3d,
            frontOnly: Boolean,
            segments: Int = RING_SEGMENTS
        ): Pair<List<Vector3d>, Boolean> {
            val normal = Vector3d(axis).normalize()
            val toEye = Vector3d(eye).sub(center)
            if (toEye.lengthSquared() < 1e-9) return emptyList<Vector3d>() to false
            toEye.normalize()
            val from = Vector3d(normal).cross(toEye)
            if (!frontOnly || from.length() < FULL_CIRCLE_THRESHOLD) {
                val (u, v) = basis(normal)
                val points = ArrayList<Vector3d>(segments)
                for (step in 0 until segments) points += ringPoint(center, u, v, radius, step * TAU / segments)
                return points to true
            }
            from.normalize()
            val forward = Vector3d(normal).cross(from)
            if (forward.dot(toEye) < 0.0) forward.negate()
            val steps = segments / 2
            val points = ArrayList<Vector3d>(steps + 1)
            for (step in 0..steps) {
                val angle = Math.PI * step / steps
                points += Vector3d(center).add(Vector3d(from).mul(cos(angle) * radius))
                    .add(Vector3d(forward).mul(sin(angle) * radius))
            }
            return points to false
        }

        fun poseBasis(rotation: Rotation): Triple<Vector3d, Vector3d, Vector3d> {
            val forward = rotation.forward()
            val right0 = rotation.right()
            val up0 = Vector3d(right0).cross(forward).normalize()
            val roll = Math.toRadians(rotation.roll)
            val c = cos(roll)
            val s = sin(roll)
            return Triple(
                forward,
                Vector3d(right0).mul(c).add(Vector3d(up0).mul(s)),
                Vector3d(up0).mul(c).sub(Vector3d(right0).mul(s))
            )
        }

        private const val FULL_CIRCLE_THRESHOLD = 0.12
    }
}
