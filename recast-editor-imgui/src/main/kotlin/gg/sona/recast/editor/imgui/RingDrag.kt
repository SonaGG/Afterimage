package gg.sona.recast.editor.imgui

import gg.sona.recast.camera.CameraPose
import org.joml.Vector3d
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

class RingDrag(val pivot: Vector3d, val axis: Vector3d, val radius: Double) {

    val u: Vector3d
    val v: Vector3d
    var onPlane = false
        private set
    var startAngle = 0.0
        private set
    private var lastAngle = 0.0
    private var tangent: FloatArray? = null
    private var startMouseX = 0f
    private var startMouseY = 0f

    var degrees = 0.0
        private set

    init {
        val (ringU, ringV) = GizmoDraw.basis(axis)
        u = ringU
        v = ringV
    }

    fun begin(
        camera: CameraPose,
        ray: DoubleArray,
        mouseX: Float,
        mouseY: Float,
        project: (Vector3d) -> FloatArray?,
        radiusPixels: Double,
    ): Boolean {
        startMouseX = mouseX
        startMouseY = mouseY
        val toEye = Vector3d(camera.position).sub(pivot).normalize()
        val grabbed = (0 until STEPS).minByOrNull { step ->
            val p = project(GizmoDraw.ringPoint(pivot, u, v, radius, step * GizmoDraw.TAU / STEPS))
                ?: return@minByOrNull Float.MAX_VALUE
            hypot((mouseX - p[0]).toDouble(), (mouseY - p[1]).toDouble()).toFloat()
        } ?: 0
        val grabAngle = grabbed * GizmoDraw.TAU / STEPS
        val grabPoint = GizmoDraw.ringPoint(pivot, u, v, radius, grabAngle)
        onPlane = abs(axis.dot(toEye)) > PLANE_THRESHOLD
        if (onPlane) {
            val hit = SceneMath.planePoint(ray, pivot, axis) ?: grabPoint
            startAngle = angleOf(hit)
        } else {
            startAngle = Math.toDegrees(grabAngle)
            val direction = Vector3d(axis).cross(Vector3d(grabPoint).sub(pivot)).normalize()
            val p0 = project(grabPoint) ?: return false
            val p1 = project(Vector3d(grabPoint).add(Vector3d(direction).mul(radius * PROBE))) ?: return false
            val dx = (p1[0] - p0[0]).toDouble()
            val dy = (p1[1] - p0[1]).toDouble()
            val length = hypot(dx, dy)
            if (length < 1e-4) return false
            val pixelsPerRadian = (length / PROBE).coerceAtLeast(radiusPixels * 0.5)
            tangent = floatArrayOf((dx / length).toFloat(), (dy / length).toFloat(), pixelsPerRadian.toFloat())
        }
        lastAngle = startAngle
        return true
    }

    fun update(ray: DoubleArray?, mouseX: Float, mouseY: Float): Double {
        if (onPlane) {
            val hit = ray?.let { SceneMath.planePoint(it, pivot, axis) }
            if (hit != null) {
                val angle = angleOf(hit)
                degrees += SceneMath.wrapDegrees(angle - lastAngle)
                lastAngle = angle
            }
        } else {
            val screen = tangent ?: return degrees
            val along = (mouseX - startMouseX) * screen[0] + (mouseY - startMouseY) * screen[1]
            degrees = Math.toDegrees((along / screen[2]).toDouble())
        }
        return degrees
    }

    private fun angleOf(point: Vector3d): Double {
        val offset = Vector3d(point).sub(pivot)
        return Math.toDegrees(atan2(offset.dot(v), offset.dot(u)))
    }

    private companion object {
        const val STEPS = 56
        const val PLANE_THRESHOLD = 0.3
        const val PROBE = 0.05
    }
}
