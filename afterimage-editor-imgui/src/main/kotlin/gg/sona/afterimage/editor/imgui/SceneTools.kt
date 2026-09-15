package gg.sona.afterimage.editor.imgui

import gg.sona.afterimage.camera.*
import gg.sona.afterimage.camera.track.SegmentMode
import gg.sona.afterimage.core.time.Nanos
import gg.sona.afterimage.editor.EditorSession
import gg.sona.afterimage.editor.Selection
import gg.sona.afterimage.editor.commands.*
import gg.sona.afterimage.editor.host.GizmoBatch
import gg.sona.afterimage.editor.host.ViewportPick
import gg.sona.afterimage.editor.imgui.SceneMath.axisOffset
import gg.sona.afterimage.editor.imgui.SceneMath.planePoint
import gg.sona.afterimage.editor.imgui.SceneMath.pointInPolygon
import gg.sona.afterimage.editor.imgui.SceneMath.segmentDistance
import gg.sona.afterimage.replay.state.interpolation.LinearInterpolation
import imgui.ImDrawList
import imgui.ImGui
import imgui.flag.*
import org.joml.Matrix3d
import org.joml.Vector3d
import kotlin.math.abs
import kotlin.math.tan

class SceneTools(private val context: EditorContext) {

    enum class Part { NONE, CENTER, AXIS_X, AXIS_Y, AXIS_Z, PLANE_X, PLANE_Y, PLANE_Z, RING_X, RING_Y, RING_Z, SCALE_X, SCALE_Y, SCALE_Z, HANDLE, BEZIER_IN, BEZIER_OUT }

    private class Handle(val keyframe: CameraKeyframe, val x: Float, val y: Float, val selected: Boolean)

    private class Drag(
        val part: Part,
        val times: Set<Long>,
        val original: CameraPath,
        val pivot: Vector3d,
        val axes: Array<Vector3d>,
        val startMouseX: Float,
        val startMouseY: Float,
    ) {
        var axis: Vector3d? = null
        var planeNormal: Vector3d? = null

        var anchor = Vector3d(pivot)
        var startOffset = 0.0
        var startPoint: Vector3d? = null
        var axisScreen: FloatArray? = null
        var handleTime = 0L

        var ring: RingDrag? = null
        var appliedDegrees = 0.0
        var label = ""
        var moved = false

        var started = false
    }

    private class Box(val startX: Float, val startY: Float)

    var consumedClick = false
        private set

    var hoveredEntity: ViewportPick? = null
        private set

    private var drag: Drag? = null
    private var box: Box? = null
    private var pendingBox: Box? = null
    private var hoverPart = Part.NONE
    private var hoverTime: Long? = null
    private var hoverPathTime: Long? = null
    private var hoverPathPoint: Vector3d? = null
    private var handles: List<Handle> = emptyList()
    private var contextTime: Long? = null
    private var lastPickNanos = 0L
    private var gizmoSize = 1.0
    private val axisAlpha = FloatArray(3) { 1f }
    private val planeAlpha = FloatArray(3) { 1f }
    private var pivot = Vector3d()
    private var axes: Array<Vector3d> = arrayOf(Vector3d(GizmoDraw.X), Vector3d(GizmoDraw.Y), Vector3d(GizmoDraw.Z))
    private var overlayTags = ArrayList<Triple<Float, Float, String>>()

    fun draw(rect: ViewRect, frame: FrameContext) {
        consumedClick = false
        overlayTags.clear()
        val gizmos = context.host.gizmos
        gizmos.clear()
        val session = context.session
        if (session == null) {
            context.host.requestPreview(null, 0, 0)
            hoveredEntity = null
            return
        }
        val io = ImGui.getIO()
        val mouseX = ImGui.getMousePosX()
        val mouseY = ImGui.getMousePosY()
        val inside = !io.wantCaptureMouse && rect.contains(
            mouseX,
            mouseY
        ) && !ImGui.isPopupOpen("scene-keyframe") && !ImGui.isPopupOpen("scene-entity")
        val camera = context.host.camera.currentPose()
        val draw = GizmoDraw(gizmos)
        draw.eye = Vector3d(camera.position)
        draw.pixelScale = worldPerPixel(rect, camera, 1.0)
        val overlay = ImGui.getBackgroundDrawList()
        overlay.pushClipRect(rect.x, rect.y, rect.x + rect.width, rect.y + rect.height, true)
        try {
            val gizmosOn = context.ui.sceneGizmos
            val free = inside
            if (context.host.camera.settings.showPath && gizmosOn) {
                collectHandles(rect, session, camera)
                path(session, draw)
                ghost(session, draw, camera)
                gizmo(rect, session, camera, draw, free, mouseX, mouseY)
                pathHover(rect, session, draw, free, mouseX, mouseY)
                keyframes(rect, session, draw, camera)
                input(rect, session, free, mouseX, mouseY)
            } else if (context.host.camera.settings.showPath) {
                handles = emptyList()
                hoverPart = Part.NONE
                hoverTime = null
                path(session, draw)
                pathHover(rect, session, draw, free, mouseX, mouseY)
            } else {
                handles = emptyList()
                hoverPart = Part.NONE
                hoverTime = null
                hoverPathTime = null
                hoverPathPoint = null
            }
            if (gizmosOn) entities(rect, session, draw, free, mouseX, mouseY, frame) else hoveredEntity = null
            box?.let {
                overlay.addRectFilled(it.startX, it.startY, mouseX, mouseY, EditorTheme.TEXT.u32(0.08f))
                overlay.addRect(it.startX, it.startY, mouseX, mouseY, EditorTheme.TEXT.u32(0.6f), 0f, 0, 1f)
            }
            for ((x, y, text) in overlayTags) tag(overlay, x, y, text)
        } finally {
            overlay.popClipRect()
        }
        if (context.ui.orientationGizmo) orientationGizmo(rect)
        speedFlash(rect)
        preview(rect, session)
        contextMenu(session)
        keyboard(session, inside)
        if (inside && drag == null && (hoverPart != Part.NONE || hoverPathTime != null || hoveredEntity != null)) ImGui.setMouseCursor(
            ImGuiMouseCursor.Hand
        )
    }

    private fun worldPerPixel(rect: ViewRect, camera: CameraPose, distance: Double): Double =
        2.0 * distance * tan(Math.toRadians(camera.fov / 2.0)) / rect.height.coerceAtLeast(1f)

    private fun collectHandles(rect: ViewRect, session: EditorSession, camera: CameraPose) {
        val selection = session.selection.keyframeTimes
        val result = ArrayList<Handle>()
        for (keyframe in session.project.camera.keyframes()) {
            if (camera.position.distance(keyframe.pose.position) < 0.5 && drag == null) continue
            val screen = project(rect, keyframe.pose.position) ?: continue
            result += Handle(keyframe, screen[0], screen[1], keyframe.timeNanos in selection)
        }
        handles = result
    }

    private fun path(session: EditorSession, draw: GizmoDraw) {
        val path = session.project.camera
        if (path.keyframes().size < 2) return
        val tolerance = context.host.camera.settings.pathToleranceBlocks
        val playhead = session.playheadNanos
        for (segment in path.tessellate(tolerance)) {
            val color = EditorTheme.modeColor(segment.mode)
            val points = segment.points
            val played = ArrayList<Vector3d>()
            val ahead = ArrayList<Vector3d>()
            for (index in points.indices) {
                val point = points[index]
                if (point.timeNanos <= playhead) played += point.position
                if (point.timeNanos >= playhead) ahead += point.position
                if (index + 1 < points.size) {
                    val next = points[index + 1]
                    if (point.timeNanos < playhead && next.timeNanos > playhead) {
                        val t = (playhead - point.timeNanos).toDouble() / (next.timeNanos - point.timeNanos)
                        val split = Vector3d(point.position).lerp(next.position, t)
                        played += split
                        ahead += Vector3d(split)
                    }
                }
            }
            stroke(draw, played, color, 0.55f)
            stroke(draw, ahead, color, 1f)
        }
    }

    private fun stroke(draw: GizmoDraw, points: List<Vector3d>, color: EditorTheme.Rgb, alpha: Float) {
        if (points.size < 2) return
        draw.polyline(points, PATH_SHADOW.abgr(0.38f * alpha), PATH_WIDTH + 2f, depthOffset = PATH_SHADOW_OFFSET)
        draw.polyline(points, color.abgr(0.95f * alpha), PATH_WIDTH)
    }

    private fun keyframes(rect: ViewRect, session: EditorSession, draw: GizmoDraw, camera: CameraPose) {
        val active = drag
        val aspect = (rect.width / rect.height).toDouble().coerceIn(1.0, 2.4)
        for (handle in handles) {
            val keyframe = handle.keyframe
            val hovered = hoverPart == Part.HANDLE && hoverTime == keyframe.timeNanos
            val dragging = active != null && keyframe.timeNanos in active.times
            val base = EditorTheme.modeColor(keyframe.mode)
            val color = when {
                dragging || handle.selected -> EditorTheme.SELECTION.abgr(1f)
                hovered -> 0xFFFFFFFF.toInt()
                else -> base.abgr(0.9f)
            }
            val distance = camera.position.distance(keyframe.pose.position)
            val depth =
                if (handle.selected) (distance * 0.09).coerceIn(0.5, 1.6) else (distance * 0.05).coerceIn(0.28, 0.8)
            draw.frustum(keyframe.pose, depth, aspect, color, if (handle.selected) 2.2f else 1.4f)
            val radius = worldPerPixel(rect, camera, distance) * (if (handle.selected) 5.5 else 4.0)
            draw.billboard(keyframe.pose.position, radius, color)
            draw.billboardRing(keyframe.pose.position, radius, EditorTheme.APP_BG.abgr(0.85f), 1.2f)
            if (context.ui.keyframeLabels && (hovered || handle.selected) && active == null) overlayTags += Triple(
                handle.x + 10f,
                handle.y - 12f,
                TimeFormat.short(keyframe.timeNanos)
            )
        }
        if (context.tool == SceneTool.MOVE) bezierHandles(rect, session, draw, camera)
    }

    private fun bezierHandles(rect: ViewRect, session: EditorSession, draw: GizmoDraw, camera: CameraPose) {
        val selected = selectedKeyframes(session)
        if (selected.size != 1) return
        val frame = selected.first()
        if (frame.mode != SegmentMode.BEZIER) return
        val keyframe = session.project.camera.position.at(frame.timeNanos) ?: return
        val handleIn = keyframe.handleIn ?: return
        val handleOut = keyframe.handleOut ?: return
        val distance = camera.position.distance(frame.pose.position)
        val radius = worldPerPixel(rect, camera, distance) * 4.0
        val color = EditorTheme.PURPLE.abgr(0.95f)
        draw.line(handleIn, frame.pose.position, EditorTheme.PURPLE.abgr(0.6f), 1.2f)
        draw.line(frame.pose.position, handleOut, EditorTheme.PURPLE.abgr(0.6f), 1.2f)
        draw.diamond(
            handleIn,
            radius,
            if (hoverPart == Part.BEZIER_IN || drag?.part == Part.BEZIER_IN) 0xFFFFFFFF.toInt() else color
        )
        draw.diamond(
            handleOut,
            radius,
            if (hoverPart == Part.BEZIER_OUT || drag?.part == Part.BEZIER_OUT) 0xFFFFFFFF.toInt() else color
        )
    }

    private fun ghost(session: EditorSession, draw: GizmoDraw, camera: CameraPose) {
        val control = context.host.camera
        val path = session.project.camera
        if (path.isEmpty || control.pathActive) return
        val time = session.playheadNanos
        if (!path.contains(time) || path.keyframeAt(time) != null) return
        val pose = session.pathPoseAt(time) ?: path.poseAt(time)
        if (pose.position.distance(camera.position) < 0.5) return
        draw.frustum(pose, 0.6, 16.0 / 9.0, EditorTheme.WARNING.abgr(0.75f), 1.4f)
        draw.cameraBody(pose, 0.18, EditorTheme.WARNING.abgr(0.9f))
    }

    private fun pathHover(
        rect: ViewRect,
        session: EditorSession,
        draw: GizmoDraw,
        inside: Boolean,
        mouseX: Float,
        mouseY: Float
    ) {
        hoverPathTime = null
        hoverPathPoint = null
        if (!inside || drag != null || box != null || hoverPart != Part.NONE) return
        val path = session.project.camera
        if (path.keyframes().size < 2) return
        val tolerance = context.host.camera.settings.pathToleranceBlocks
        var bestDistance = PATH_GRAB
        var bestTime = 0L
        var bestPoint: Vector3d? = null
        for (segment in path.tessellate(tolerance)) {
            val points = segment.points
            var previous = project(rect, points[0].position)
            for (index in 1 until points.size) {
                val current = project(rect, points[index].position)
                if (previous != null && current != null) {
                    val distance = segmentDistance(mouseX, mouseY, previous[0], previous[1], current[0], current[1])
                    if (distance < bestDistance) {
                        bestDistance = distance
                        bestTime = (points[index - 1].timeNanos + points[index].timeNanos) / 2
                        bestPoint = Vector3d(points[index - 1].position).lerp(points[index].position, 0.5)
                    }
                }
                previous = current
            }
        }
        val point = bestPoint ?: return
        if (handles.any { abs(it.x - mouseX) < CENTER_GRAB && abs(it.y - mouseY) < CENTER_GRAB }) return
        hoverPathTime = bestTime
        hoverPathPoint = point
        val camera = context.host.camera.currentPose()
        val radius = worldPerPixel(rect, camera, camera.position.distance(point)) * 5.0
        draw.billboardRing(point, radius, 0xFFFFFFFF.toInt(), 1.6f)
        project(rect, point)?.let {
            overlayTags += Triple(
                it[0] + 10f,
                it[1] - 12f,
                "${TimeFormat.short(bestTime)}  double-click to insert"
            )
        }
    }

    private fun selectedKeyframes(session: EditorSession): List<CameraKeyframe> =
        session.selection.keyframeTimes.mapNotNull { session.project.camera.keyframeAt(it) }.sortedBy { it.timeNanos }

    private fun pivotOf(frames: List<CameraKeyframe>): Vector3d {
        val pivot = Vector3d()
        for (frame in frames) pivot.add(frame.pose.position)
        if (frames.isNotEmpty()) pivot.div(frames.size.toDouble())
        return pivot
    }

    private fun gizmoAxes(frames: List<CameraKeyframe>): Array<Vector3d> {
        if (!context.localSpace || frames.size != 1) return arrayOf(
            Vector3d(GizmoDraw.X),
            Vector3d(GizmoDraw.Y),
            Vector3d(GizmoDraw.Z)
        )
        val (forward, right, up) = GizmoDraw.poseBasis(frames.first().pose.rotation)
        return arrayOf(right, up, forward)
    }

    private fun gizmo(
        rect: ViewRect,
        session: EditorSession,
        camera: CameraPose,
        draw: GizmoDraw,
        inside: Boolean,
        mouseX: Float,
        mouseY: Float
    ) {
        hoverPart = Part.NONE
        hoverTime = null
        val active = drag
        val io = ImGui.getIO()
        val frames = selectedKeyframes(session)
        val tool = context.tool
        if (frames.isNotEmpty()) {
            pivot = pivotOf(frames)
            axes = if (active != null && tool != SceneTool.ROTATE) active.axes else gizmoAxes(frames)
        }
        var hovered = active?.part ?: Part.NONE
        var best = Float.MAX_VALUE
        val showGizmo = frames.isNotEmpty() && tool != SceneTool.VIEW && camera.position.distance(pivot) > 0.4
        val rings = arrayOfNulls<Pair<List<Vector3d>, Boolean>>(3)
        if (showGizmo) {
            val distance = camera.position.distance(pivot)
            gizmoSize = worldPerPixel(rect, camera, distance) * GIZMO_PIXELS
            val toEye = Vector3d(camera.position).sub(pivot).normalize()
            for (index in 0 until 3) {
                val alignment = Math.abs(axes[index].dot(toEye))
                axisAlpha[index] = 1f - fade(alignment, AXIS_FADE_START, AXIS_FADE_END)
                planeAlpha[index] = fade(alignment, PLANE_FADE_START, PLANE_FADE_END)
            }
            if (tool == SceneTool.ROTATE) {
                val radius = gizmoSize * RING_RADIUS
                for (ring in 0 until 3) rings[ring] = GizmoDraw.ringPoints(
                    pivot,
                    axes[ring],
                    radius,
                    camera.position,
                    frontOnly = !(active != null && active.part == RING_PARTS[ring])
                )
            }
            val center = project(rect, pivot)
            if (center != null && inside && active == null && !io.keyAlt) {
                if (tool == SceneTool.MOVE || tool == SceneTool.SCALE) {
                    val centerDistance =
                        Math.hypot((mouseX - center[0]).toDouble(), (mouseY - center[1]).toDouble()).toFloat()
                    if (centerDistance < CENTER_GRAB) {
                        hovered = Part.CENTER
                        best = centerDistance
                    }
                }
                if (tool == SceneTool.MOVE) {
                    for (index in 0 until 3) {
                        if (planeAlpha[index] < HIT_ALPHA) continue
                        val quad = planeCorners(index, camera).mapNotNull { project(rect, it) }
                        if (quad.size == 4 && pointInPolygon(mouseX, mouseY, quad) && best > 0f) {
                            hovered = PLANE_PARTS[index]
                            best = 0f
                        }
                    }
                    for (index in 0 until 3) {
                        if (axisAlpha[index] < HIT_ALPHA) continue
                        val tip = project(rect, Vector3d(pivot).add(Vector3d(axes[index]).mul(gizmoSize))) ?: continue
                        val d = segmentDistance(mouseX, mouseY, center[0], center[1], tip[0], tip[1])
                        if (d < AXIS_GRAB && d < best) {
                            best = d
                            hovered = AXIS_PARTS[index]
                        }
                    }
                }
                if (tool == SceneTool.SCALE) {
                    for (index in 0 until 3) {
                        if (axisAlpha[index] < HIT_ALPHA) continue
                        val tip = project(rect, Vector3d(pivot).add(Vector3d(axes[index]).mul(gizmoSize))) ?: continue
                        val d = Math.hypot((mouseX - tip[0]).toDouble(), (mouseY - tip[1]).toDouble()).toFloat()
                        if (d < CENTER_GRAB && d < best) {
                            best = d
                            hovered = SCALE_PARTS[index]
                        }
                        val along = segmentDistance(mouseX, mouseY, center[0], center[1], tip[0], tip[1])
                        if (along < AXIS_GRAB && along < best) {
                            best = along
                            hovered = SCALE_PARTS[index]
                        }
                    }
                }
                if (tool == SceneTool.ROTATE) {
                    for (ring in 0 until 3) {
                        val (points, closed) = rings[ring] ?: continue
                        val screen = points.map { project(rect, it) }
                        val count = if (closed) screen.size else screen.size - 1
                        for (index in 0 until count) {
                            val a = screen[index] ?: continue
                            val b = screen[(index + 1) % screen.size] ?: continue
                            val d = segmentDistance(mouseX, mouseY, a[0], a[1], b[0], b[1])
                            if (d < RING_GRAB && d < best) {
                                best = d
                                hovered = RING_PARTS[ring]
                            }
                        }
                    }
                }
            }
            if (tool == SceneTool.MOVE && frames.size == 1 && frames.first().mode == SegmentMode.BEZIER && inside && active == null && !io.keyAlt) {
                val keyframe = session.project.camera.position.at(frames.first().timeNanos)
                val handleIn = keyframe?.handleIn?.let { project(rect, it) }
                val handleOut = keyframe?.handleOut?.let { project(rect, it) }
                if (handleIn != null) {
                    val d = Math.hypot((mouseX - handleIn[0]).toDouble(), (mouseY - handleIn[1]).toDouble()).toFloat()
                    if (d < CENTER_GRAB && d < best) {
                        best = d
                        hovered = Part.BEZIER_IN
                    }
                }
                if (handleOut != null) {
                    val d = Math.hypot((mouseX - handleOut[0]).toDouble(), (mouseY - handleOut[1]).toDouble()).toFloat()
                    if (d < CENTER_GRAB && d < best) {
                        best = d
                        hovered = Part.BEZIER_OUT
                    }
                }
            }
        }
        if (inside && active == null && !io.keyAlt && hovered == Part.NONE) {
            var handleBest = HANDLE_GRAB
            for (handle in handles) {
                val distance = Math.hypot((mouseX - handle.x).toDouble(), (mouseY - handle.y).toDouble()).toFloat()
                if (distance < handleBest) {
                    handleBest = distance
                    hovered = Part.HANDLE
                    hoverTime = handle.keyframe.timeNanos
                }
            }
        }
        hoverPart = hovered
        if (!showGizmo) return
        draw.layer = GizmoBatch.OVERLAY
        try {
            when (tool) {
                SceneTool.MOVE -> drawMoveGizmo(draw, camera, hovered)
                SceneTool.ROTATE -> drawRotateGizmo(draw, camera, rings, hovered, active)
                SceneTool.SCALE -> drawScaleGizmo(draw, hovered, frames.size == 1)
                SceneTool.VIEW -> Unit
            }
        } finally {
            draw.layer = GizmoBatch.WORLD
        }
        active?.let {
            if (it.label.isNotEmpty()) project(
                rect,
                pivot
            )?.let { center -> overlayTags += Triple(center[0] + 18f, center[1] + 18f, it.label) }
        }
    }

    private fun fade(value: Double, from: Double, to: Double): Float {
        val t = ((value - from) / (to - from)).coerceIn(0.0, 1.0)
        return (t * t * (3.0 - 2.0 * t)).toFloat()
    }

    private fun axisColor(index: Int, lit: Boolean, alpha: Float = 1f): Int =
        if (lit) SELECTED.abgr(alpha) else AXIS_COLORS[index].abgr(alpha)

    private fun planeCorners(index: Int, camera: CameraPose): List<Vector3d> {
        val u = Vector3d(axes[(index + 1) % 3])
        val v = Vector3d(axes[(index + 2) % 3])
        val toEye = Vector3d(camera.position).sub(pivot)
        if (u.dot(toEye) < 0.0) u.negate()
        if (v.dot(toEye) < 0.0) v.negate()
        val offset = gizmoSize * PLANE_OFFSET
        val size = gizmoSize * PLANE_SIZE
        val base = Vector3d(pivot).add(Vector3d(u).mul(offset)).add(Vector3d(v).mul(offset))
        return listOf(
            Vector3d(base),
            Vector3d(base).add(Vector3d(u).mul(size)),
            Vector3d(base).add(Vector3d(u).mul(size)).add(Vector3d(v).mul(size)),
            Vector3d(base).add(Vector3d(v).mul(size)),
        )
    }

    private fun drawMoveGizmo(draw: GizmoDraw, camera: CameraPose, hovered: Part) {
        for (index in 0 until 3) {
            val visible = planeAlpha[index]
            if (visible < 0.02f) continue
            val lit = hovered == PLANE_PARTS[index]
            val corners = planeCorners(index, camera)
            draw.quad(
                corners[0],
                corners[1],
                corners[2],
                corners[3],
                axisColor(index, lit, (if (lit) 0.5f else 0.22f) * visible),
                shaded = false
            )
            draw.polyline(corners, axisColor(index, lit, 0.95f * visible), PLANE_OUTLINE_WIDTH, closed = true)
        }
        for (index in 0 until 3) {
            val visible = axisAlpha[index]
            if (visible < 0.02f) continue
            val lit = hovered == AXIS_PARTS[index]
            val color = axisColor(index, lit, visible)
            val axis = axes[index]
            val start = Vector3d(pivot).add(Vector3d(axis).mul(gizmoSize * SHAFT_GAP))
            val base = Vector3d(pivot).add(Vector3d(axis).mul(gizmoSize * (1.0 - HEAD_LENGTH)))
            val tip = Vector3d(pivot).add(Vector3d(axis).mul(gizmoSize))
            draw.polyline(listOf(start, base), color, if (lit) SHAFT_WIDTH + 0.6f else SHAFT_WIDTH)
            val (u, v) = GizmoDraw.basis(axis)
            draw.cone(base, tip, u, v, gizmoSize * HEAD_RADIUS, color, GizmoDraw.HEAD_SIDES)
        }
        val centerLit = hovered == Part.CENTER
        draw.billboardSquare(
            pivot,
            gizmoSize * CENTER_SIZE,
            if (centerLit) SELECTED.abgr(0.45f) else 0x2EFFFFFF,
            if (centerLit) SELECTED.abgr(1f) else 0xF0FFFFFF.toInt(),
            PLANE_OUTLINE_WIDTH
        )
    }

    private fun drawRotateGizmo(
        draw: GizmoDraw,
        camera: CameraPose,
        rings: Array<Pair<List<Vector3d>, Boolean>?>,
        hovered: Part,
        active: Drag?
    ) {
        val radius = gizmoSize * RING_RADIUS
        val toEye = Vector3d(camera.position).sub(pivot)
        draw.circle(pivot, toEye, radius, 0x40FFFFFF, 1f, GizmoDraw.RING_SEGMENTS)
        draw.circle(pivot, toEye, radius * OUTER_RING, 0x99FFFFFF.toInt(), 1.6f, GizmoDraw.RING_SEGMENTS)
        for (ring in 0 until 3) {
            val (points, closed) = rings[ring] ?: continue
            val lit = hovered == RING_PARTS[ring]
            draw.polyline(points, axisColor(ring, lit), if (lit) RING_WIDTH + 0.6f else RING_WIDTH, closed = closed)
        }
        if (active == null || !active.started || active.part !in RING_PARTS) return
        val ring = RING_PARTS.indexOf(active.part)
        val drag = active.ring ?: return
        val from = Math.toRadians(drag.startAngle)
        val to = Math.toRadians(drag.startAngle + active.appliedDegrees)
        draw.fan(pivot, drag.u, drag.v, radius, from, to, axisColor(ring, false, 0.28f))
        val startPoint = GizmoDraw.ringPoint(pivot, drag.u, drag.v, radius, from)
        val endPoint = GizmoDraw.ringPoint(pivot, drag.u, drag.v, radius, to)
        draw.polyline(listOf(pivot, startPoint), 0x80FFFFFF.toInt(), 1.2f)
        draw.polyline(listOf(pivot, endPoint), SELECTED.abgr(0.95f), 1.6f)
    }

    private fun drawScaleGizmo(draw: GizmoDraw, hovered: Part, single: Boolean) {
        val half = gizmoSize * 0.06
        if (!single) {
            for (index in 0 until 3) {
                val visible = axisAlpha[index]
                if (visible < 0.02f) continue
                val lit = hovered == SCALE_PARTS[index]
                val color = axisColor(index, lit, visible)
                val axis = axes[index]
                val start = Vector3d(pivot).add(Vector3d(axis).mul(gizmoSize * SHAFT_GAP))
                val tip = Vector3d(pivot).add(Vector3d(axis).mul(gizmoSize))
                draw.polyline(
                    listOf(start, Vector3d(pivot).add(Vector3d(axis).mul(gizmoSize - half))),
                    color,
                    SHAFT_WIDTH
                )
                draw.cube(tip, half, color, axes[0], axes[1], axes[2])
            }
        }
        val centerLit = hovered == Part.CENTER
        draw.cube(
            pivot,
            gizmoSize * (if (centerLit) 0.11 else 0.09),
            if (centerLit) SELECTED.abgr(1f) else 0xF0FFFFFF.toInt(),
            axes[0],
            axes[1],
            axes[2]
        )
    }

    private fun entities(
        rect: ViewRect,
        session: EditorSession,
        draw: GizmoDraw,
        inside: Boolean,
        mouseX: Float,
        mouseY: Float,
        frame: FrameContext
    ) {
        val replay = session.replay
        if (replay == null) {
            hoveredEntity = null
            return
        }
        val gestureBusy = ImGui.isMouseDown(ImGuiMouseButton.Right) || drag != null || box != null
        if (!inside || gestureBusy) {
            hoveredEntity = null
        } else if (hoverPart == Part.NONE && hoverPathTime == null && frame.nowNanos - lastPickNanos > PICK_INTERVAL) {
            lastPickNanos = frame.nowNanos
            hoveredEntity = context.host.pickEntity((mouseX - rect.x) / rect.width, (mouseY - rect.y) / rect.height)
        } else if (hoverPart != Part.NONE || hoverPathTime != null) {
            hoveredEntity = null
        }
        if (!context.ui.entityBoxes) return
        val hovered = hoveredEntity
        val selected = context.selectedEntityId
        if (hovered != null && hovered.entityId != selected) {
            entityBox(
                draw,
                hovered.x,
                hovered.y,
                hovered.z,
                hovered.halfWidth,
                hovered.height,
                0xDDFFFFFF.toInt(),
                1.6f
            )
            project(
                rect,
                Vector3d(hovered.x, hovered.y + hovered.height + 0.15, hovered.z)
            )?.let { overlayTags += Triple(it[0] - 8f, it[1] - 12f, hovered.name) }
        }
        if (selected != null) {
            val shadow = replay.shadow
            val local = shadow.localPlayer
            if (selected == local.entityId && local.hasPosition) {
                entityBox(draw, local.x, local.y, local.z, 0.3, 1.8, EditorTheme.SELECTION.abgr(1f), 2f)
            } else {
                val entity = shadow.entities[selected]
                if (entity != null && !entity.dead) {
                    val pose = entity.poseAt(replay.positionNanos, LinearInterpolation)
                    val player = entity.isPlayer
                    entityBox(
                        draw,
                        pose.x,
                        pose.y,
                        pose.z,
                        if (player) 0.3 else 0.35,
                        if (player) 1.8 else 0.7,
                        EditorTheme.SELECTION.abgr(1f),
                        2f
                    )
                }
            }
        }
    }

    private fun entityBox(
        draw: GizmoDraw,
        x: Double,
        y: Double,
        z: Double,
        halfWidth: Double,
        height: Double,
        color: Int,
        width: Float
    ) {
        val pad = 0.05
        draw.cornerBox(
            Vector3d(x - halfWidth - pad, y - pad, z - halfWidth - pad),
            Vector3d(x + halfWidth + pad, y + height + pad, z + halfWidth + pad),
            color,
            width,
            0.28
        )
    }

    private fun tag(list: ImDrawList, x: Float, y: Float, label: String) {
        EditorFonts.with(EditorFonts.small) {
            val width = Widgets.textWidth(label)
            val height = ImGui.getFontSize()
            val padX = EditorFonts.px(6f)
            val padY = EditorFonts.px(3f)
            list.addRectFilled(
                x,
                y - padY,
                x + width + padX * 2f,
                y + height + padY,
                EditorTheme.PANEL_RAISED.u32(0.92f),
                EditorFonts.px(5f)
            )
            list.addRect(
                x,
                y - padY,
                x + width + padX * 2f,
                y + height + padY,
                EditorTheme.BORDER_SOFT.u32(0.14f),
                EditorFonts.px(5f)
            )
            list.addText(x + padX, y, EditorTheme.TEXT.u32, label)
        }
    }

    private fun input(rect: ViewRect, session: EditorSession, inside: Boolean, mouseX: Float, mouseY: Float) {
        val io = ImGui.getIO()
        val active = drag
        if (active != null) {
            consumedClick = true
            if (ImGui.isKeyPressed(ImGuiKey.Escape, false)) {
                restore(session, active.original)
                drag = null
                return
            }
            if (ImGui.isMouseDown(ImGuiMouseButton.Left)) updateDrag(
                rect,
                session,
                active,
                mouseX,
                mouseY,
                io.keyCtrl
            ) else finishDrag(session, active)
            return
        }
        val pending = pendingBox
        if (pending != null && box == null) {
            if (!ImGui.isMouseDown(ImGuiMouseButton.Left)) {
                pendingBox = null
            } else if (Math.abs(mouseX - pending.startX) > 4f || Math.abs(mouseY - pending.startY) > 4f) {
                box = pending
                pendingBox = null
            }
        }
        val current = box
        if (current != null) {
            consumedClick = true
            if (ImGui.isMouseDown(ImGuiMouseButton.Left)) return
            val minX = minOf(current.startX, mouseX)
            val maxX = maxOf(current.startX, mouseX)
            val minY = minOf(current.startY, mouseY)
            val maxY = maxOf(current.startY, mouseY)
            val picked =
                handles.filter { it.x in minX..maxX && it.y in minY..maxY }.map { it.keyframe.timeNanos }.toSet()
            session.selection =
                if (io.keyShift) Selection(keyframeTimes = session.selection.keyframeTimes + picked) else Selection(
                    keyframeTimes = picked
                )
            if (picked.isNotEmpty()) context.selectEntity(null)
            box = null
            return
        }
        if (!inside || io.keyAlt) return
        if (ImGui.isMouseClicked(ImGuiMouseButton.Right) && hoverPart == Part.HANDLE) {
            val time = hoverTime ?: return
            if (time !in session.selection.keyframeTimes) session.selection = Selection(keyframeTimes = setOf(time))
            contextTime = time
            ImGui.openPopup("scene-keyframe")
            consumedClick = true
            return
        }
        if (!ImGui.isMouseClicked(ImGuiMouseButton.Left)) return
        when (hoverPart) {
            Part.HANDLE -> {
                consumedClick = true
                val time = hoverTime ?: return
                if (ImGui.isMouseDoubleClicked(ImGuiMouseButton.Left)) {
                    session.replay?.seek(time)
                    return
                }
                session.selection = when {
                    io.keyShift && time in session.selection.keyframeTimes -> Selection(keyframeTimes = session.selection.keyframeTimes - time)
                    io.keyShift -> Selection(keyframeTimes = session.selection.keyframeTimes + time)
                    time in session.selection.keyframeTimes -> session.selection
                    else -> Selection(keyframeTimes = setOf(time))
                }
                context.selectEntity(null)
                if (time in session.selection.keyframeTimes && context.tool == SceneTool.MOVE) beginDrag(
                    rect,
                    session,
                    Part.HANDLE,
                    mouseX,
                    mouseY
                )
            }

            Part.NONE -> {
                val pathTime = hoverPathTime
                if (pathTime != null && ImGui.isMouseDoubleClicked(ImGuiMouseButton.Left)) {
                    consumedClick = true
                    insertKeyframe(session, pathTime)
                } else if (!context.host.camera.gestureActive && hoveredEntity == null) {
                    pendingBox = Box(mouseX, mouseY)
                }
            }

            else -> {
                consumedClick = true
                beginDrag(rect, session, hoverPart, mouseX, mouseY)
            }
        }
    }

    private fun insertKeyframe(session: EditorSession, time: Long) {
        val path = session.project.camera
        val previous = path.keyframes().lastOrNull { it.timeNanos <= time }
        session.execute(
            SetCameraKeyframe(
                time,
                path.poseAt(time),
                previous?.easing ?: Easing.LINEAR,
                previous?.mode ?: session.defaultKeyframeMode
            )
        )
        session.selection = Selection(keyframeTimes = setOf(time))
    }

    private fun beginDrag(rect: ViewRect, session: EditorSession, part: Part, mouseX: Float, mouseY: Float) {
        val frames = selectedKeyframes(session)
        if (frames.isEmpty()) return
        val ray = ray(rect, mouseX, mouseY) ?: return
        val pivot = pivotOf(frames)
        val camera = context.host.camera.currentPose()
        val axes = gizmoAxes(frames)
        val effectivePart = if (part == Part.HANDLE && context.tool == SceneTool.VIEW) return else part
        val active = Drag(
            effectivePart,
            frames.map { it.timeNanos }.toSet(),
            session.project.camera.copy(),
            pivot,
            axes,
            mouseX,
            mouseY
        )
        when (effectivePart) {
            Part.AXIS_X, Part.AXIS_Y, Part.AXIS_Z -> {
                val axis = axes[AXIS_PARTS.indexOf(effectivePart)]
                active.axis = axis
                active.startOffset = axisOffset(ray, pivot, axis)
            }

            Part.PLANE_X, Part.PLANE_Y, Part.PLANE_Z -> {
                val normal = axes[PLANE_PARTS.indexOf(effectivePart)]
                active.planeNormal = normal
                active.startPoint = planePoint(ray, pivot, normal) ?: return
            }

            Part.HANDLE, Part.CENTER -> {
                if (context.tool == SceneTool.SCALE && effectivePart == Part.CENTER) {
                    active.startPoint = null
                } else {
                    if (effectivePart == Part.HANDLE) hoverTime?.let { time ->
                        frames.firstOrNull { it.timeNanos == time }?.let { active.anchor.set(it.pose.position) }
                    }
                    val normal = camera.rotation.forward()
                    active.planeNormal = normal
                    active.startPoint = planePoint(ray, active.anchor, normal) ?: return
                }
            }

            Part.BEZIER_IN, Part.BEZIER_OUT -> {
                val single = frames.singleOrNull() ?: return
                active.handleTime = single.timeNanos
                val keyframe = session.project.camera.position.at(active.handleTime) ?: return
                val handle = (if (effectivePart == Part.BEZIER_IN) keyframe.handleIn else keyframe.handleOut) ?: return
                val normal = camera.rotation.forward()
                active.planeNormal = normal
                active.startPoint = planePoint(ray, handle, normal) ?: return
            }

            Part.RING_X, Part.RING_Y, Part.RING_Z -> if (!beginRingDrag(
                    rect,
                    active,
                    camera,
                    ray,
                    mouseX,
                    mouseY
                )
            ) return

            Part.SCALE_X, Part.SCALE_Y, Part.SCALE_Z -> {
                val index = SCALE_PARTS.indexOf(effectivePart)
                val axis = axes[index]
                active.axis = axis
                val center = project(rect, pivot) ?: return
                val tip = project(rect, Vector3d(pivot).add(Vector3d(axis).mul(gizmoSize))) ?: return
                val dx = tip[0] - center[0]
                val dy = tip[1] - center[1]
                val length = Math.hypot(dx.toDouble(), dy.toDouble()).toFloat().coerceAtLeast(1f)
                active.axisScreen = floatArrayOf(dx / length, dy / length, length)
            }

            Part.NONE -> return
        }
        drag = active
    }

    private fun beginRingDrag(
        rect: ViewRect,
        active: Drag,
        camera: CameraPose,
        ray: DoubleArray,
        mouseX: Float,
        mouseY: Float
    ): Boolean {
        val ring = RING_PARTS.indexOf(active.part)
        val drag = RingDrag(Vector3d(active.pivot), Vector3d(active.axes[ring]), gizmoSize * RING_RADIUS)
        if (!drag.begin(camera, ray, mouseX, mouseY, { project(rect, it) }, GIZMO_PIXELS * RING_RADIUS)) return false
        active.ring = drag
        return true
    }

    private fun updateDrag(
        rect: ViewRect,
        session: EditorSession,
        active: Drag,
        mouseX: Float,
        mouseY: Float,
        snap: Boolean
    ) {
        if (!active.started) {
            if (Math.hypot(
                    (mouseX - active.startMouseX).toDouble(),
                    (mouseY - active.startMouseY).toDouble()
                ) < DRAG_THRESHOLD
            ) return
            active.started = true
        }
        val transformed = active.original.copy()
        val frames = active.original.keyframes().filter { it.timeNanos in active.times }
        when (active.part) {
            Part.AXIS_X, Part.AXIS_Y, Part.AXIS_Z, Part.PLANE_X, Part.PLANE_Y, Part.PLANE_Z, Part.HANDLE -> {
                if (context.tool == SceneTool.VIEW) return
                val ray = ray(rect, mouseX, mouseY) ?: return
                val delta = Vector3d()
                val axis = active.axis
                if (axis != null) {
                    delta.set(axis).mul(axisOffset(ray, active.pivot, axis) - active.startOffset)
                } else {
                    val point = planePoint(ray, active.anchor, active.planeNormal ?: return) ?: return
                    delta.set(point).sub(active.startPoint ?: return)
                }
                if (snap) {
                    delta.x = Math.round(delta.x * 2.0) / 2.0
                    delta.y = Math.round(delta.y * 2.0) / 2.0
                    delta.z = Math.round(delta.z * 2.0) / 2.0
                }
                for (frame in frames) translate(transformed, frame, delta)
                active.label = String.format("%+.2f  %+.2f  %+.2f", delta.x, delta.y, delta.z)
                active.moved = delta.lengthSquared() > 1e-8
            }

            Part.CENTER -> if (context.tool == SceneTool.SCALE) {
                var factor = Math.exp(((mouseX - active.startMouseX) - (mouseY - active.startMouseY)) * 0.006)
                if (snap) factor = maxOf(0.1, Math.round(factor * 10.0) / 10.0)
                if (frames.size == 1) {
                    val frame = frames.first()
                    val fov = (frame.pose.fov * factor).coerceIn(10.0, 150.0)
                    transformed.keyframe(
                        frame.timeNanos,
                        CameraPose(Vector3d(frame.pose.position), frame.pose.rotation, fov),
                        frame.easing,
                        frame.mode
                    )
                    active.label = String.format("FOV %.0f°", fov)
                } else {
                    for (frame in frames) {
                        val offset = Vector3d(frame.pose.position).sub(active.pivot).mul(factor)
                        translate(transformed, frame, Vector3d(active.pivot).add(offset).sub(frame.pose.position))
                    }
                    active.label = String.format("%.2fx", factor)
                }
                active.moved = Math.abs(factor - 1.0) > 1e-6
            } else {
                val ray = ray(rect, mouseX, mouseY) ?: return
                val point = planePoint(ray, active.anchor, active.planeNormal ?: return) ?: return
                val delta = Vector3d(point).sub(active.startPoint ?: return)
                if (snap) {
                    delta.x = Math.round(delta.x * 2.0) / 2.0
                    delta.y = Math.round(delta.y * 2.0) / 2.0
                    delta.z = Math.round(delta.z * 2.0) / 2.0
                }
                for (frame in frames) translate(transformed, frame, delta)
                active.label = String.format("%+.2f  %+.2f  %+.2f", delta.x, delta.y, delta.z)
                active.moved = delta.lengthSquared() > 1e-8
            }

            Part.SCALE_X, Part.SCALE_Y, Part.SCALE_Z -> {
                val screen = active.axisScreen ?: return
                val along =
                    ((mouseX - active.startMouseX) * screen[0] + (mouseY - active.startMouseY) * screen[1]) / screen[2].coerceAtLeast(
                        20f
                    )
                var factor = maxOf(0.05, 1.0 + along)
                if (snap) factor = maxOf(0.1, Math.round(factor * 10.0) / 10.0)
                val axis = active.axis ?: return
                for (frame in frames) {
                    val offset = Vector3d(frame.pose.position).sub(active.pivot)
                    val projected = offset.dot(axis)
                    val scaled = Vector3d(offset).add(Vector3d(axis).mul(projected * (factor - 1.0)))
                    translate(transformed, frame, Vector3d(active.pivot).add(scaled).sub(frame.pose.position))
                }
                active.label = String.format("%s %.2fx", AXIS_LABELS[SCALE_PARTS.indexOf(active.part)], factor)
                active.moved = Math.abs(factor - 1.0) > 1e-6
            }

            Part.RING_X, Part.RING_Y, Part.RING_Z -> {
                val ring = RING_PARTS.indexOf(active.part)
                val axis = active.axes[ring]
                val raw = active.ring?.update(ray(rect, mouseX, mouseY), mouseX, mouseY) ?: return
                val delta = if (snap) Math.round(raw / 15.0) * 15.0 else raw
                active.appliedDegrees = delta
                val local = context.localSpace && frames.size == 1
                if (local) {
                    val frame = frames.first()
                    val r = frame.pose.rotation
                    val rotated = when (ring) {
                        0 -> Rotation(r.yaw, (r.pitch - delta).coerceIn(-90.0, 90.0), r.roll)
                        1 -> Rotation(r.yaw - delta, r.pitch, r.roll)
                        else -> Rotation(r.yaw, r.pitch, r.roll - delta)
                    }
                    transformed.keyframe(
                        frame.timeNanos,
                        CameraPose(Vector3d(frame.pose.position), rotated, frame.pose.fov),
                        frame.easing,
                        frame.mode
                    )
                    active.label = String.format("%s %+.1f°", arrayOf("Pitch", "Yaw", "Roll")[ring], delta)
                } else {
                    val matrix = Matrix3d().rotate(Math.toRadians(delta), axis.x, axis.y, axis.z)
                    for (frame in frames) {
                        val offset = matrix.transform(Vector3d(frame.pose.position).sub(active.pivot))
                        val look = Rotation.lookingAt(
                            Vector3d(),
                            matrix.transform(frame.pose.rotation.forward()),
                            frame.pose.rotation.roll
                        )
                        val target = Vector3d(active.pivot).add(offset)
                        translate(transformed, frame, Vector3d(target).sub(frame.pose.position))
                        transformed.rotation.update(frame.timeNanos) { it.copy(value = look) }
                    }
                    active.label = String.format("%s %+.1f°", AXIS_LABELS[ring], delta)
                }
                active.moved = Math.abs(delta) > 1e-6
            }

            Part.BEZIER_IN, Part.BEZIER_OUT -> {
                val ray = ray(rect, mouseX, mouseY) ?: return
                val keyframe = active.original.position.at(active.handleTime) ?: return
                val handle = (if (active.part == Part.BEZIER_IN) keyframe.handleIn else keyframe.handleOut) ?: return
                val point = planePoint(ray, handle, active.planeNormal ?: return) ?: return
                val delta = Vector3d(point).sub(active.startPoint ?: return)
                if (snap) {
                    delta.x = Math.round(delta.x * 2.0) / 2.0
                    delta.y = Math.round(delta.y * 2.0) / 2.0
                    delta.z = Math.round(delta.z * 2.0) / 2.0
                }
                val moved = Vector3d(handle).add(delta)
                transformed.position.update(active.handleTime) {
                    if (active.part == Part.BEZIER_IN) it.copy(handleIn = moved) else it.copy(
                        handleOut = moved
                    )
                }
                active.label = String.format("%+.2f  %+.2f  %+.2f", delta.x, delta.y, delta.z)
                active.moved = delta.lengthSquared() > 1e-8
            }

            Part.NONE -> Unit
        }
        ReplaceCameraPath.copyInto(session.project.camera, transformed)
    }

    private fun translate(path: CameraPath, frame: CameraKeyframe, delta: Vector3d) {
        path.position.update(frame.timeNanos) {
            it.copy(
                value = Vector3d(it.value).add(delta),
                handleIn = it.handleIn?.let { h -> Vector3d(h).add(delta) },
                handleOut = it.handleOut?.let { h -> Vector3d(h).add(delta) },
            )
        }
    }

    private fun restore(session: EditorSession, original: CameraPath) {
        ReplaceCameraPath.copyInto(session.project.camera, original)
    }

    private fun finishDrag(session: EditorSession, active: Drag) {
        val result = session.project.camera.copy()
        restore(session, active.original)
        drag = null
        if (!active.moved) return
        val count = active.times.size
        val label = when (active.part) {
            Part.RING_X, Part.RING_Y, Part.RING_Z -> if (count == 1) "Rotate keyframe" else "Rotate $count keyframes"
            Part.CENTER -> if (context.tool == SceneTool.SCALE) (if (count == 1) "Change keyframe FOV" else "Scale $count keyframes") else (if (count == 1) "Move keyframe" else "Move $count keyframes")
            Part.SCALE_X, Part.SCALE_Y, Part.SCALE_Z -> "Scale $count keyframes"
            Part.BEZIER_IN, Part.BEZIER_OUT -> "Adjust bezier handles"
            else -> if (count == 1) "Move keyframe" else "Move $count keyframes"
        }
        if (active.part == Part.BEZIER_IN || active.part == Part.BEZIER_OUT) {
            val keyframe = result.position.at(active.handleTime) ?: return
            session.execute(SetPositionHandles(active.handleTime, keyframe.handleIn, keyframe.handleOut))
            return
        }
        session.execute(ReplaceCameraPath(result, label))
    }

    private fun contextMenu(session: EditorSession) {
        if (!ImGui.beginPopup("scene-keyframe")) return
        try {
            val time = contextTime ?: return
            val frame = session.project.camera.keyframeAt(time) ?: return
            val targets = session.selection.keyframeTimes.ifEmpty { setOf(time) }
            Widgets.smallText(
                "Keyframe ${TimeFormat.clock(time)}${if (targets.size > 1) "  (${targets.size} selected)" else ""}",
                EditorTheme.TEXT_DIM.u32
            )
            ImGui.separator()
            if (Menus.item("Go to keyframe")) session.replay?.seek(time)
            if (Menus.item("Look through")) lookThrough(frame.pose)
            if (Menus.item("Frame", "F")) context.host.camera.frame(
                frame.pose.position.x,
                frame.pose.position.y,
                frame.pose.position.z,
                1.5
            )
            if (Menus.item("Update from current view")) session.execute(
                SetCameraKeyframe(
                    time,
                    context.host.camera.currentPose(),
                    frame.easing,
                    frame.mode
                )
            )
            ImGui.separator()
            if (ImGui.beginMenu("Interpolation")) {
                for (mode in SegmentMode.entries) if (Menus.item(
                        mode.label,
                        "",
                        frame.mode == mode
                    )
                ) session.execute(SetKeyframeMode(targets, mode))
                ImGui.endMenu()
            }
            ImGui.separator()
            if (Menus.item(
                    if (targets.size > 1) "Delete ${targets.size} keyframes" else "Delete keyframe",
                    "Del"
                )
            ) {
                session.execute(RemoveKeyframes(targets))
                session.selection = Selection.NONE
            }
        } finally {
            ImGui.endPopup()
        }
    }

    fun lookThrough(pose: CameraPose) {
        val control = context.host.camera
        if (control.settings.mode != CameraMode.FREE) {
            control.settings.mode = CameraMode.FREE
            control.apply()
        }
        control.teleport(pose)
    }

    private fun keyboard(session: EditorSession, inside: Boolean) {
        val io = ImGui.getIO()
        if (io.wantTextInput || !inside || context.host.camera.gestureActive) return
        if (io.keyCtrl && ImGui.isKeyPressed(ImGuiKey.A, false)) session.selection =
            Selection(keyframeTimes = session.project.camera.keyframeTimes().toSet())
        if (ImGui.isKeyPressed(ImGuiKey.Delete, false) && session.selection.keyframeTimes.isNotEmpty()) {
            session.execute(RemoveKeyframes(session.selection.keyframeTimes))
            session.selection = Selection.NONE
        }
        if (ImGui.isKeyPressed(ImGuiKey.Escape, false) && drag == null) {
            if (!session.selection.isEmpty) session.selection = Selection.NONE
            else if (context.selectedEntityId != null) context.selectEntity(null)
        }
    }

    private fun preview(rect: ViewRect, session: EditorSession) {
        val control = context.host.camera
        val path = session.project.camera
        var pose: CameraPose? = null
        var time = 0L
        hoverPathTime?.let {
            pose = session.pathPoseAt(it) ?: path.poseAt(it)
            time = it
        }
        if (pose == null) {
            val hovered = hoverTime?.takeIf { hoverPart == Part.HANDLE }?.let { path.keyframeAt(it) }
            val frame = hovered ?: selectedKeyframes(session).lastOrNull()
            if (frame != null) {
                pose = frame.pose
                time = frame.timeNanos
            }
        }
        if (pose == null && !path.isEmpty && !control.pathActive && path.contains(session.playheadNanos)) {
            pose = session.pathPoseAt(session.playheadNanos) ?: path.poseAt(session.playheadNanos)
            time = session.playheadNanos
        }
        val target = pose
        if (!context.ui.cameraPreview || target == null || !control.settings.showPath) {
            context.host.requestPreview(null, 0, 0)
            return
        }
        val width = (rect.width * 0.26f).toInt().coerceIn(160, 640)
        val height = (width * rect.height / rect.width).toInt().coerceAtLeast(90)
        context.host.requestPreview(target, width, height)
        val texture = context.host.previewTexture() ?: return
        val flags =
            ImGuiWindowFlags.NoDecoration or ImGuiWindowFlags.NoDocking or ImGuiWindowFlags.AlwaysAutoResize or ImGuiWindowFlags.NoSavedSettings or ImGuiWindowFlags.NoFocusOnAppearing or ImGuiWindowFlags.NoNav or ImGuiWindowFlags.NoScrollbar
        ImGui.setNextWindowPos(
            rect.x + rect.width - EditorFonts.px(12f),
            rect.y + rect.height - EditorFonts.px(12f),
            ImGuiCond.Always,
            1f,
            1f
        )
        ImGui.setNextWindowBgAlpha(0.96f)
        ImGui.pushStyleVar(imgui.flag.ImGuiStyleVar.WindowRounding, EditorFonts.px(8f))
        ImGui.pushStyleVar(imgui.flag.ImGuiStyleVar.WindowPadding, EditorFonts.px(3f), EditorFonts.px(3f))
        ImGui.pushStyleVar(imgui.flag.ImGuiStyleVar.WindowBorderSize, 1f)
        try {
            if (ImGui.begin("##camera-preview", flags)) {
                ImGui.image(texture[0].toLong(), width.toFloat(), height.toFloat(), 0f, 1f, 1f, 0f)
                val hovered = ImGui.isItemHovered()
                if (hovered && ImGui.isMouseDoubleClicked(ImGuiMouseButton.Left)) lookThrough(target)
                val draw = ImGui.getWindowDrawList()
                val x = ImGui.getWindowPosX() + EditorFonts.px(10f)
                val y = ImGui.getWindowPosY() + EditorFonts.px(8f)
                EditorFonts.with(EditorFonts.smallMedium) {
                    val label = TimeFormat.short(time)
                    val labelWidth = Widgets.textWidth(label)
                    draw.addRectFilled(
                        x - EditorFonts.px(5f),
                        y - EditorFonts.px(2f),
                        x + labelWidth + EditorFonts.px(5f),
                        y + ImGui.getFontSize() + EditorFonts.px(2f),
                        EditorTheme.APP_BG.u32(0.7f),
                        EditorFonts.px(4f)
                    )
                    draw.addText(x, y, EditorTheme.TEXT.u32, label)
                }
                if (hovered) Widgets.hint("Camera view at ${TimeFormat.clock(time)}. Double-click to look through it.")
            }
            ImGui.end()
        } finally {
            ImGui.popStyleVar(3)
        }
    }

    private fun orientationGizmo(rect: ViewRect) {
        val control = context.host.camera
        val size = EditorFonts.px(84f)
        ImGui.setNextWindowPos(
            rect.x + rect.width - size - EditorFonts.px(8f),
            rect.y + EditorFonts.px(8f),
            ImGuiCond.Always
        )
        ImGui.setNextWindowSize(size, size, ImGuiCond.Always)
        val flags =
            ImGuiWindowFlags.NoDecoration or ImGuiWindowFlags.NoDocking or ImGuiWindowFlags.NoSavedSettings or ImGuiWindowFlags.NoFocusOnAppearing or ImGuiWindowFlags.NoNav or ImGuiWindowFlags.NoBackground or ImGuiWindowFlags.NoScrollbar
        if (ImGui.begin("##view-gizmo", flags)) {
            val draw = ImGui.getWindowDrawList()
            val originX = ImGui.getWindowPosX() + size / 2f
            val originY = ImGui.getWindowPosY() + size / 2f
            val radius = size * 0.3f
            val pose = control.currentPose()
            val basis = Matrix3d().rotateX(Math.toRadians(pose.rotation.pitch))
                .rotateY(Math.toRadians(pose.rotation.yaw + 180.0))
            val entries = ArrayList<Triple<Int, Vector3d, Boolean>>(6)
            for (axis in 0 until 3) {
                entries += Triple(axis, basis.transform(Vector3d(WORLD_AXES[axis])), true)
                entries += Triple(axis, basis.transform(Vector3d(WORLD_AXES[axis]).negate()), false)
            }
            entries.sortBy { it.second.z }
            if (ImGui.isWindowHovered()) draw.addCircleFilled(
                originX,
                originY,
                radius + EditorFonts.px(12f),
                EditorTheme.APP_BG.u32(0.55f),
                28
            )
            for ((axis, vector, positive) in entries) {
                val x = originX + vector.x.toFloat() * radius
                val y = originY - vector.y.toFloat() * radius
                val color = AXIS_COLORS[axis].u32
                val dot = EditorFonts.px(6.5f)
                ImGui.setCursorScreenPos(x - dot, y - dot)
                ImGui.pushID(axis * 2 + if (positive) 0 else 1)
                try {
                    ImGui.invisibleButton("tip", dot * 2f, dot * 2f)
                    val hovered = ImGui.isItemHovered()
                    if (positive) {
                        draw.addLine(originX, originY, x, y, color, 2f)
                        draw.addCircleFilled(x, y, if (hovered) dot + 1.5f else dot, color, 18)
                        EditorFonts.with(EditorFonts.label) {
                            val label = AXIS_LABELS[axis]
                            draw.addText(
                                x - Widgets.textWidth(label) / 2f,
                                y - ImGui.getFontSize() / 2f,
                                0xFF0F0F10.toInt(),
                                label
                            )
                        }
                    } else {
                        draw.addCircleFilled(x, y, if (hovered) dot else dot - 1.5f, AXIS_COLORS[axis].u32(0.35f), 18)
                        draw.addCircle(x, y, if (hovered) dot else dot - 1.5f, color, 18, 1.2f)
                    }
                    if (ImGui.isItemClicked(ImGuiMouseButton.Left)) {
                        val direction =
                            if (positive) Vector3d(WORLD_AXES[axis]).negate() else Vector3d(WORLD_AXES[axis])
                        val yaw = Math.toDegrees(Math.atan2(-direction.x, direction.z))
                        val pitch = Math.toDegrees(-Math.asin(direction.y.coerceIn(-1.0, 1.0)))
                        control.snapView(if (axis == 1) pose.rotation.yaw else yaw, pitch)
                    }
                    if (hovered) Widgets.hint("Look along ${if (positive) "-" else "+"}${AXIS_LABELS[axis]}")
                } finally {
                    ImGui.popID()
                }
            }
        }
        ImGui.end()
    }

    private fun speedFlash(rect: ViewRect) {
        val control = context.host.camera
        val remaining = control.speedFlashUntilNanos() - System.nanoTime()
        if (remaining <= 0L) return
        val alpha = (remaining.toDouble() / Nanos.ofMillis(400)).coerceIn(0.0, 1.0).toFloat()
        val label = String.format("%.1f blocks/s", control.settings.freeSpeed)
        EditorFonts.with(EditorFonts.bodyMedium) {
            val width = Widgets.textWidth(label)
            val draw = ImGui.getForegroundDrawList()
            val x = rect.x + rect.width / 2f - width / 2f
            val y = rect.y + rect.height - EditorFonts.px(56f)
            draw.addRectFilled(
                x - EditorFonts.px(14f),
                y - EditorFonts.px(7f),
                x + width + EditorFonts.px(14f),
                y + ImGui.getFontSize() + EditorFonts.px(7f),
                EditorTheme.PANEL_RAISED.u32(0.92f * alpha),
                EditorFonts.px(16f)
            )
            draw.addText(x, y, EditorTheme.TEXT.u32(alpha), label)
        }
    }

    private fun project(rect: ViewRect, position: Vector3d): FloatArray? {
        val projected = context.host.project(position.x, position.y, position.z) ?: return null
        if (projected[2] < -1f || projected[2] > 1f) return null
        return floatArrayOf(rect.x + projected[0] * rect.width, rect.y + projected[1] * rect.height)
    }

    private fun projectDepth(rect: ViewRect, position: Vector3d): FloatArray? {
        val projected = context.host.project(position.x, position.y, position.z) ?: return null
        if (projected[2] < -1f || projected[2] > 1f) return null
        return floatArrayOf(rect.x + projected[0] * rect.width, rect.y + projected[1] * rect.height, projected[2])
    }

    private fun ray(rect: ViewRect, mouseX: Float, mouseY: Float): DoubleArray? =
        context.host.ray((mouseX - rect.x) / rect.width, (mouseY - rect.y) / rect.height)

    private companion object {
        val WORLD_AXES = arrayOf(Vector3d(1.0, 0.0, 0.0), Vector3d(0.0, 1.0, 0.0), Vector3d(0.0, 0.0, 1.0))
        val AXIS_PARTS = listOf(Part.AXIS_X, Part.AXIS_Y, Part.AXIS_Z)
        val PLANE_PARTS = listOf(Part.PLANE_X, Part.PLANE_Y, Part.PLANE_Z)
        val RING_PARTS = listOf(Part.RING_X, Part.RING_Y, Part.RING_Z)
        val SCALE_PARTS = listOf(Part.SCALE_X, Part.SCALE_Y, Part.SCALE_Z)
        val AXIS_COLORS = listOf(EditorTheme.AXIS_X, EditorTheme.AXIS_Y, EditorTheme.AXIS_Z)
        val AXIS_LABELS = arrayOf("X", "Y", "Z")
        val SELECTED = EditorTheme.Rgb(0xF6F232)
        val PATH_SHADOW = EditorTheme.Rgb(0x000000)
        const val PATH_WIDTH = 2.5f
        const val PATH_SHADOW_OFFSET = 0.004
        const val GIZMO_PIXELS = 96.0
        const val HEAD_LENGTH = 0.22
        const val HEAD_RADIUS = 0.06
        const val SHAFT_GAP = 0.12
        const val PLANE_OFFSET = 0.3
        const val PLANE_SIZE = 0.18
        const val RING_RADIUS = 0.85
        const val OUTER_RING = 1.12
        const val CENTER_SIZE = 0.07
        const val SHAFT_WIDTH = 2.2f
        const val PLANE_OUTLINE_WIDTH = 1.6f
        const val RING_WIDTH = 2.5f
        const val AXIS_FADE_START = 0.94
        const val AXIS_FADE_END = 0.995
        const val PLANE_FADE_START = 0.06
        const val PLANE_FADE_END = 0.24
        const val HIT_ALPHA = 0.5f
        const val DRAG_THRESHOLD = 3.0
        const val AXIS_GRAB = 8f
        const val CENTER_GRAB = 11f
        const val RING_GRAB = 8f
        const val PATH_GRAB = 7f
        const val HANDLE_GRAB = 10f
        val PICK_INTERVAL: Long = Nanos.ofMillis(40)
    }
}
