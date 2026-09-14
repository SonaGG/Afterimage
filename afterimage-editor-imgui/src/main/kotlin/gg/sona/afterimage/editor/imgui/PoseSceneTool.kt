package gg.sona.afterimage.editor.imgui

import gg.sona.afterimage.camera.CameraPose
import gg.sona.afterimage.editor.EditorSession
import gg.sona.afterimage.editor.host.GizmoBatch
import gg.sona.afterimage.editor.pose.BodyPart
import gg.sona.afterimage.editor.pose.PartPose
import imgui.ImGui
import imgui.flag.ImGuiKey
import imgui.flag.ImGuiMouseButton
import org.joml.Vector3d
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.tan

class PoseSceneTool(private val context: EditorContext) {
    private class Drag(
        val entityId: Int,
        val part: BodyPart,
        val axis: Int,
        val ring: RingDrag,
        val startRotation: Vector3d,
        val startMouseX: Float,
        val startMouseY: Float,
    ) {
        var started = false
        var degrees = 0.0
    }

    var hoverLimb: BodyPart? = null
        private set
    private var hoverRing = -1
    private var drag: Drag? = null
    var consumedClick = false
        private set
    var consumedEscape = false
        private set

    val active: Boolean get() = drag != null || hoverLimb != null || hoverRing >= 0

    fun draw(
        rect: ViewRect,
        session: EditorSession,
        camera: CameraPose,
        draw: GizmoDraw,
        inside: Boolean,
        mouseX: Float,
        mouseY: Float,
        project: (Vector3d) -> FloatArray?,
        ray: () -> DoubleArray?,
        tags: MutableList<Triple<Float, Float, String>>,
    ): Boolean {
        consumedClick = false
        consumedEscape = false
        hoverLimb = null
        hoverRing = -1
        val entityId = context.selectedEntityId
        if (entityId == null || context.tool != SceneTool.ROTATE || !PoseTools.poseable(session, entityId)) {
            drag = null
            return false
        }
        val model = context.host.entityModel(entityId)
        if (model == null) {
            drag = null
            return false
        }
        val math = PoseMath(model)
        val active = drag
        if (active != null && active.entityId != entityId) drag = null
        val selected = context.selectedBodyPart
        val io = ImGui.getIO()

        val hulls = HashMap<BodyPart, List<FloatArray>>()
        for (part in BodyPart.entries) {
            val corners = math.corners(part, math.rotation(part))
            val screen = corners.map { project(it) }
            if (screen.all { it != null }) hulls[part] = SceneMath.convexHull(screen.map { it!! })
        }
        var hovered: BodyPart? = null
        if (inside && drag == null && !io.keyAlt) {
            var best = Float.MAX_VALUE
            for ((part, hull) in hulls) {
                if (hull.size < 3 || !SceneMath.pointInPolygon(mouseX, mouseY, hull)) continue
                val area = polygonArea(hull)
                if (area < best) {
                    best = area
                    hovered = part
                }
            }
        }

        val rings: Array<Pair<List<Vector3d>, Boolean>?> = arrayOfNulls(3)
        var axes: Array<Vector3d> = emptyArray()
        var pivot: Vector3d? = null
        var radius = 0.0
        if (selected != null) {
            pivot = math.pivot(selected)
            axes = math.ringAxes()
            val distance = camera.position.distance(pivot)
            radius = worldPerPixel(rect, camera, distance) * GIZMO_PIXELS
            for (index in 0 until 3) rings[index] = GizmoDraw.ringPoints(
                pivot,
                axes[index],
                radius,
                camera.position,
                frontOnly = !(drag != null && drag!!.axis == index)
            )
            if (inside && drag == null && !io.keyAlt) {
                var best = RING_GRAB
                for (index in 0 until 3) {
                    val (points, closed) = rings[index] ?: continue
                    val screen = points.map { project(it) }
                    val count = if (closed) screen.size else screen.size - 1
                    for (segment in 0 until count) {
                        val a = screen[segment] ?: continue
                        val b = screen[(segment + 1) % screen.size] ?: continue
                        val d = SceneMath.segmentDistance(mouseX, mouseY, a[0], a[1], b[0], b[1])
                        if (d < best) {
                            best = d
                            hoverRing = index
                        }
                    }
                }
            }
        }
        if (hoverRing < 0) hoverLimb = hovered

        // draw
        for (part in BodyPart.entries) {
            val corners = math.corners(part, math.rotation(part))
            val color: Int
            val width: Float
            when (part) {
                selected -> {
                    color = EditorTheme.SELECTION.abgr(1f)
                    width = 2f
                }

                hoverLimb -> {
                    color = 0xFFFFFFFF.toInt()
                    width = 1.8f
                }

                else -> {
                    color = 0x66FFFFFF
                    width = 1f
                }
            }
            box(draw, corners, color, width)
        }
        if (selected != null && pivot != null) {
            draw.layer = GizmoBatch.OVERLAY
            try {
                draw.billboard(pivot, radius * 0.08, 0xF0FFFFFF.toInt())
                for (index in 0 until 3) {
                    val (points, closed) = rings[index] ?: continue
                    val lit = hoverRing == index || drag?.axis == index
                    draw.polyline(
                        points,
                        if (lit) SELECTED.abgr(1f) else AXIS_COLORS[index].abgr(0.95f),
                        if (lit) 2.8f else 2.2f,
                        closed = closed
                    )
                }
                val current = drag
                if (current != null && current.started) {
                    val from = Math.toRadians(current.ring.startAngle)
                    val to = Math.toRadians(current.ring.startAngle + current.degrees)
                    draw.fan(
                        pivot,
                        current.ring.u,
                        current.ring.v,
                        radius,
                        from,
                        to,
                        AXIS_COLORS[current.axis].abgr(0.28f)
                    )
                    draw.polyline(
                        listOf(
                            pivot,
                            GizmoDraw.ringPoint(pivot, current.ring.u, current.ring.v, radius, from)
                        ), 0x80FFFFFF.toInt(), 1.2f
                    )
                    draw.polyline(
                        listOf(pivot, GizmoDraw.ringPoint(pivot, current.ring.u, current.ring.v, radius, to)),
                        SELECTED.abgr(0.95f),
                        1.6f
                    )
                    project(pivot)?.let {
                        tags += Triple(
                            it[0] + 16f,
                            it[1] + 16f,
                            String.format("%s %+.1f°", selected.label, current.degrees)
                        )
                    }
                }
            } finally {
                draw.layer = GizmoBatch.WORLD
            }
        }
        if (hoverLimb != null && selected != hoverLimb && drag == null) {
            val hull = hulls[hoverLimb!!]
            if (hull != null) {
                val top = hull.minByOrNull { it[1] }
                if (top != null) tags += Triple(top[0] + 8f, top[1] - 14f, hoverLimb!!.label)
            }
        }

        val current = drag
        if (current != null) {
            consumedClick = true
            if (ImGui.isKeyPressed(ImGuiKey.Escape, false) || !ImGui.isMouseDown(ImGuiMouseButton.Left)) {
                drag = null
                return true
            }
            updateDrag(rect, session, math, current, axes, mouseX, mouseY, ray, io.keyCtrl)
            return true
        }
        if (!inside || io.keyAlt) return false
        if (ImGui.isKeyPressed(ImGuiKey.Escape, false) && selected != null && !io.wantTextInput) {
            context.selectedBodyPart = null
            consumedEscape = true
            return true
        }
        if (!ImGui.isMouseClicked(ImGuiMouseButton.Left)) return hoverLimb != null || hoverRing >= 0
        if (hoverRing >= 0 && selected != null && pivot != null) {
            consumedClick = true
            beginDrag(
                session,
                math,
                entityId,
                selected,
                hoverRing,
                axes[hoverRing],
                pivot,
                radius,
                camera,
                mouseX,
                mouseY,
                project,
                ray
            )
            return true
        }
        val limb = hoverLimb
        if (limb != null) {
            consumedClick = true
            context.selectedBodyPart = limb
            return true
        }
        return false
    }

    private fun beginDrag(
        session: EditorSession,
        math: PoseMath,
        entityId: Int,
        part: BodyPart,
        axis: Int,
        axisVector: Vector3d,
        pivot: Vector3d,
        radius: Double,
        camera: CameraPose,
        mouseX: Float,
        mouseY: Float,
        project: (Vector3d) -> FloatArray?,
        ray: () -> DoubleArray?,
    ) {
        val start = ray() ?: return
        val ring = RingDrag(Vector3d(pivot), Vector3d(axisVector), radius)
        if (!ring.begin(camera, start, mouseX, mouseY, project, GIZMO_PIXELS)) return
        val posed = PoseTools.currentPose(session, entityId)[part]
        val startRotation =
            if (posed != null) PoseMath.radians(Vector3d(posed.x, posed.y, posed.z)) else math.rotation(part)
        drag = Drag(entityId, part, axis, ring, startRotation, mouseX, mouseY)
    }

    private fun updateDrag(
        rect: ViewRect,
        session: EditorSession,
        math: PoseMath,
        active: Drag,
        axes: Array<Vector3d>,
        mouseX: Float,
        mouseY: Float,
        ray: () -> DoubleArray?,
        snap: Boolean,
    ) {
        if (!active.started) {
            if (hypot(
                    (mouseX - active.startMouseX).toDouble(),
                    (mouseY - active.startMouseY).toDouble()
                ) < DRAG_THRESHOLD
            ) return
            active.started = true
        }
        val raw = active.ring.update(ray(), mouseX, mouseY)
        val degrees = if (snap) (raw / 15.0).roundToInt() * 15.0 else raw
        if (degrees == active.degrees && active.started) return
        active.degrees = degrees
        val rotated = PoseMath.degrees(math.rotated(active.startRotation, axes[active.axis], degrees))
        PoseTools.setPart(
            session,
            active.entityId,
            active.part,
            PartPose(PoseTools.wrap(rotated.x), PoseTools.wrap(rotated.y), PoseTools.wrap(rotated.z), 1.0)
        )
    }

    private fun box(draw: GizmoDraw, corners: List<Vector3d>, color: Int, width: Float) {
        for ((a, b) in GizmoDraw.BOX_EDGES) draw.line(corners[a], corners[b], color, width)
    }

    private fun polygonArea(polygon: List<FloatArray>): Float {
        var area = 0f
        for (index in polygon.indices) {
            val a = polygon[index]
            val b = polygon[(index + 1) % polygon.size]
            area += a[0] * b[1] - b[0] * a[1]
        }
        return abs(area) * 0.5f
    }

    private fun worldPerPixel(rect: ViewRect, camera: CameraPose, distance: Double): Double =
        2.0 * distance * tan(Math.toRadians(camera.fov / 2.0)) / rect.height.coerceAtLeast(1f)

    private companion object {
        val AXIS_COLORS = listOf(EditorTheme.AXIS_X, EditorTheme.AXIS_Y, EditorTheme.AXIS_Z)
        val SELECTED = EditorTheme.Rgb(0xF6F232)
        const val GIZMO_PIXELS = 58.0
        const val RING_GRAB = 8f
        const val DRAG_THRESHOLD = 3.0
    }
}
