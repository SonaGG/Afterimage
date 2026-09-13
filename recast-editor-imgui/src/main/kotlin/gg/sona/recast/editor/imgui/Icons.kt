package gg.sona.recast.editor.imgui

import imgui.ImDrawList
import imgui.ImGui
import kotlin.math.cos
import kotlin.math.sin

// TODO: use proper SVG icons in the UI rewrite
//       till then, hello Opus 5 Ultracode!
object Icons {
    fun draw(list: ImDrawList, icon: Icon, x: Float, y: Float, size: Float, color: Int, thickness: Float = 1.5f) {
        val cx = x + size / 2f
        val cy = y + size / 2f
        val h = size / 2f
        when (icon) {
            Icon.PLAY -> list.addTriangleFilled(
                cx - h * 0.55f,
                cy - h * 0.7f,
                cx - h * 0.55f,
                cy + h * 0.7f,
                cx + h * 0.7f,
                cy,
                color
            )

            Icon.PAUSE -> {
                list.addRectFilled(cx - h * 0.6f, cy - h * 0.7f, cx - h * 0.15f, cy + h * 0.7f, color)
                list.addRectFilled(cx + h * 0.15f, cy - h * 0.7f, cx + h * 0.6f, cy + h * 0.7f, color)
            }

            Icon.STOP -> list.addRectFilled(cx - h * 0.6f, cy - h * 0.6f, cx + h * 0.6f, cy + h * 0.6f, color, 1f)
            Icon.STEP_BACK -> {
                list.addRectFilled(cx - h * 0.75f, cy - h * 0.7f, cx - h * 0.45f, cy + h * 0.7f, color)
                list.addTriangleFilled(
                    cx + h * 0.7f,
                    cy - h * 0.7f,
                    cx + h * 0.7f,
                    cy + h * 0.7f,
                    cx - h * 0.3f,
                    cy,
                    color
                )
            }

            Icon.STEP_FORWARD -> {
                list.addTriangleFilled(
                    cx - h * 0.7f,
                    cy - h * 0.7f,
                    cx - h * 0.7f,
                    cy + h * 0.7f,
                    cx + h * 0.3f,
                    cy,
                    color
                )
                list.addRectFilled(cx + h * 0.45f, cy - h * 0.7f, cx + h * 0.75f, cy + h * 0.7f, color)
            }

            Icon.SKIP_START -> {
                list.addRectFilled(cx - h * 0.8f, cy - h * 0.7f, cx - h * 0.55f, cy + h * 0.7f, color)
                list.addTriangleFilled(
                    cx + h * 0.15f,
                    cy - h * 0.7f,
                    cx + h * 0.15f,
                    cy + h * 0.7f,
                    cx - h * 0.5f,
                    cy,
                    color
                )
                list.addTriangleFilled(
                    cx + h * 0.85f,
                    cy - h * 0.7f,
                    cx + h * 0.85f,
                    cy + h * 0.7f,
                    cx + h * 0.2f,
                    cy,
                    color
                )
            }

            Icon.SKIP_END -> {
                list.addTriangleFilled(
                    cx - h * 0.85f,
                    cy - h * 0.7f,
                    cx - h * 0.85f,
                    cy + h * 0.7f,
                    cx - h * 0.2f,
                    cy,
                    color
                )
                list.addTriangleFilled(
                    cx - h * 0.15f,
                    cy - h * 0.7f,
                    cx - h * 0.15f,
                    cy + h * 0.7f,
                    cx + h * 0.5f,
                    cy,
                    color
                )
                list.addRectFilled(cx + h * 0.55f, cy - h * 0.7f, cx + h * 0.8f, cy + h * 0.7f, color)
            }

            Icon.REWIND -> {
                list.addTriangleFilled(
                    cx + h * 0.05f,
                    cy - h * 0.7f,
                    cx + h * 0.05f,
                    cy + h * 0.7f,
                    cx - h * 0.85f,
                    cy,
                    color
                )
                list.addTriangleFilled(cx + h * 0.9f, cy - h * 0.7f, cx + h * 0.9f, cy + h * 0.7f, cx, cy, color)
            }

            Icon.FAST_FORWARD -> {
                list.addTriangleFilled(cx - h * 0.9f, cy - h * 0.7f, cx - h * 0.9f, cy + h * 0.7f, cx, cy, color)
                list.addTriangleFilled(
                    cx - h * 0.05f,
                    cy - h * 0.7f,
                    cx - h * 0.05f,
                    cy + h * 0.7f,
                    cx + h * 0.85f,
                    cy,
                    color
                )
            }

            Icon.RECORD -> list.addCircleFilled(cx, cy, h * 0.6f, color, 20)
            Icon.LOOP -> {
                list.addCircle(cx, cy, h * 0.6f, color, 24, thickness)
                list.addTriangleFilled(
                    cx + h * 0.6f,
                    cy - h * 0.35f,
                    cx + h * 0.95f,
                    cy + h * 0.05f,
                    cx + h * 0.25f,
                    cy + h * 0.05f,
                    color
                )
            }

            Icon.KEYFRAME -> diamond(list, cx, cy, h * 0.65f, color, true)
            Icon.KEYFRAME_ADD -> {
                diamond(list, cx, cy, h * 0.65f, color, false, thickness)
                list.addLine(cx - h * 0.3f, cy, cx + h * 0.3f, cy, color, thickness)
                list.addLine(cx, cy - h * 0.3f, cx, cy + h * 0.3f, color, thickness)
            }

            Icon.MARKER -> {
                list.addRectFilled(cx - h * 0.5f, cy - h * 0.7f, cx + h * 0.5f, cy + h * 0.2f, color, 1f)
                list.addTriangleFilled(
                    cx - h * 0.5f,
                    cy + h * 0.2f,
                    cx + h * 0.5f,
                    cy + h * 0.2f,
                    cx,
                    cy + h * 0.75f,
                    color
                )
            }

            Icon.MARK_IN -> {
                list.addLine(cx - h * 0.6f, cy - h * 0.7f, cx - h * 0.6f, cy + h * 0.7f, color, thickness * 1.4f)
                list.addTriangleFilled(
                    cx - h * 0.35f,
                    cy - h * 0.5f,
                    cx - h * 0.35f,
                    cy + h * 0.5f,
                    cx + h * 0.55f,
                    cy,
                    color
                )
            }

            Icon.MARK_OUT -> {
                list.addTriangleFilled(
                    cx + h * 0.35f,
                    cy - h * 0.5f,
                    cx + h * 0.35f,
                    cy + h * 0.5f,
                    cx - h * 0.55f,
                    cy,
                    color
                )
                list.addLine(cx + h * 0.6f, cy - h * 0.7f, cx + h * 0.6f, cy + h * 0.7f, color, thickness * 1.4f)
            }

            Icon.CAMERA -> {
                list.addRect(cx - h * 0.8f, cy - h * 0.45f, cx + h * 0.25f, cy + h * 0.5f, color, 1.5f, 0, thickness)
                list.addTriangleFilled(
                    cx + h * 0.3f,
                    cy,
                    cx + h * 0.85f,
                    cy - h * 0.45f,
                    cx + h * 0.85f,
                    cy + h * 0.45f,
                    color
                )
            }

            Icon.EYE -> {
                list.addBezierQuadratic(cx - h * 0.85f, cy, cx, cy - h * 0.9f, cx + h * 0.85f, cy, color, thickness, 12)
                list.addBezierQuadratic(cx - h * 0.85f, cy, cx, cy + h * 0.9f, cx + h * 0.85f, cy, color, thickness, 12)
                list.addCircleFilled(cx, cy, h * 0.25f, color, 12)
            }

            Icon.EYE_OFF -> {
                list.addBezierQuadratic(cx - h * 0.85f, cy, cx, cy - h * 0.9f, cx + h * 0.85f, cy, color, thickness, 12)
                list.addBezierQuadratic(cx - h * 0.85f, cy, cx, cy + h * 0.9f, cx + h * 0.85f, cy, color, thickness, 12)
                list.addLine(cx - h * 0.7f, cy + h * 0.7f, cx + h * 0.7f, cy - h * 0.7f, color, thickness)
            }

            Icon.LOCK -> {
                list.addRectFilled(cx - h * 0.55f, cy - h * 0.05f, cx + h * 0.55f, cy + h * 0.7f, color, 1.5f)
                list.addBezierQuadratic(
                    cx - h * 0.35f,
                    cy - h * 0.05f,
                    cx,
                    cy - h * 1.0f,
                    cx + h * 0.35f,
                    cy - h * 0.05f,
                    color,
                    thickness,
                    10
                )
            }

            Icon.UNLOCK -> {
                list.addRectFilled(cx - h * 0.55f, cy - h * 0.05f, cx + h * 0.55f, cy + h * 0.7f, color, 1.5f)
                list.addBezierQuadratic(
                    cx - h * 0.35f,
                    cy - h * 0.05f,
                    cx - h * 0.4f,
                    cy - h * 1.0f,
                    cx + h * 0.4f,
                    cy - h * 0.6f,
                    color,
                    thickness,
                    10
                )
            }

            Icon.PLUS -> {
                list.addLine(cx - h * 0.6f, cy, cx + h * 0.6f, cy, color, thickness)
                list.addLine(cx, cy - h * 0.6f, cx, cy + h * 0.6f, color, thickness)
            }

            Icon.MINUS -> list.addLine(cx - h * 0.6f, cy, cx + h * 0.6f, cy, color, thickness)
            Icon.CLOSE -> {
                list.addLine(cx - h * 0.5f, cy - h * 0.5f, cx + h * 0.5f, cy + h * 0.5f, color, thickness)
                list.addLine(cx - h * 0.5f, cy + h * 0.5f, cx + h * 0.5f, cy - h * 0.5f, color, thickness)
            }

            Icon.CHEVRON_DOWN -> {
                list.addLine(cx - h * 0.5f, cy - h * 0.25f, cx, cy + h * 0.25f, color, thickness)
                list.addLine(cx, cy + h * 0.25f, cx + h * 0.5f, cy - h * 0.25f, color, thickness)
            }

            Icon.CHEVRON_UP -> {
                list.addLine(cx - h * 0.5f, cy + h * 0.25f, cx, cy - h * 0.25f, color, thickness)
                list.addLine(cx, cy - h * 0.25f, cx + h * 0.5f, cy + h * 0.25f, color, thickness)
            }

            Icon.CHEVRON_RIGHT -> {
                list.addLine(cx - h * 0.25f, cy - h * 0.5f, cx + h * 0.25f, cy, color, thickness)
                list.addLine(cx + h * 0.25f, cy, cx - h * 0.25f, cy + h * 0.5f, color, thickness)
            }

            Icon.SEARCH -> {
                list.addCircle(cx - h * 0.15f, cy - h * 0.15f, h * 0.5f, color, 16, thickness)
                list.addLine(cx + h * 0.2f, cy + h * 0.2f, cx + h * 0.7f, cy + h * 0.7f, color, thickness * 1.3f)
            }

            Icon.SETTINGS -> {
                list.addCircle(cx, cy, h * 0.55f, color, 8, thickness * 1.8f)
                list.addCircleFilled(cx, cy, h * 0.2f, color, 10)
            }

            Icon.FULLSCREEN -> {
                corner(list, cx - h * 0.7f, cy - h * 0.7f, h * 0.35f, h * 0.35f, color, thickness)
                corner(list, cx + h * 0.7f, cy - h * 0.7f, -h * 0.35f, h * 0.35f, color, thickness)
                corner(list, cx - h * 0.7f, cy + h * 0.7f, h * 0.35f, -h * 0.35f, color, thickness)
                corner(list, cx + h * 0.7f, cy + h * 0.7f, -h * 0.35f, -h * 0.35f, color, thickness)
            }

            Icon.ZOOM_IN -> {
                list.addCircle(cx - h * 0.15f, cy - h * 0.15f, h * 0.5f, color, 16, thickness)
                list.addLine(cx + h * 0.2f, cy + h * 0.2f, cx + h * 0.7f, cy + h * 0.7f, color, thickness * 1.3f)
                list.addLine(cx - h * 0.4f, cy - h * 0.15f, cx + h * 0.1f, cy - h * 0.15f, color, thickness)
                list.addLine(cx - h * 0.15f, cy - h * 0.4f, cx - h * 0.15f, cy + h * 0.1f, color, thickness)
            }

            Icon.ZOOM_OUT -> {
                list.addCircle(cx - h * 0.15f, cy - h * 0.15f, h * 0.5f, color, 16, thickness)
                list.addLine(cx + h * 0.2f, cy + h * 0.2f, cx + h * 0.7f, cy + h * 0.7f, color, thickness * 1.3f)
                list.addLine(cx - h * 0.4f, cy - h * 0.15f, cx + h * 0.1f, cy - h * 0.15f, color, thickness)
            }

            Icon.FIT -> {
                list.addRect(cx - h * 0.7f, cy - h * 0.5f, cx + h * 0.7f, cy + h * 0.5f, color, 1f, 0, thickness)
                list.addLine(cx - h * 0.35f, cy, cx + h * 0.35f, cy, color, thickness)
                list.addTriangleFilled(
                    cx - h * 0.5f,
                    cy,
                    cx - h * 0.25f,
                    cy - h * 0.2f,
                    cx - h * 0.25f,
                    cy + h * 0.2f,
                    color
                )
                list.addTriangleFilled(
                    cx + h * 0.5f,
                    cy,
                    cx + h * 0.25f,
                    cy - h * 0.2f,
                    cx + h * 0.25f,
                    cy + h * 0.2f,
                    color
                )
            }

            Icon.MAGNET -> {
                list.addBezierQuadratic(
                    cx - h * 0.55f,
                    cy + h * 0.6f,
                    cx - h * 0.55f,
                    cy - h * 0.9f,
                    cx,
                    cy - h * 0.7f,
                    color,
                    thickness * 1.6f,
                    10
                )
                list.addBezierQuadratic(
                    cx,
                    cy - h * 0.7f,
                    cx + h * 0.55f,
                    cy - h * 0.9f,
                    cx + h * 0.55f,
                    cy + h * 0.6f,
                    color,
                    thickness * 1.6f,
                    10
                )
                list.addLine(cx - h * 0.75f, cy + h * 0.6f, cx - h * 0.35f, cy + h * 0.6f, color, thickness * 1.6f)
                list.addLine(cx + h * 0.35f, cy + h * 0.6f, cx + h * 0.75f, cy + h * 0.6f, color, thickness * 1.6f)
            }

            Icon.FOLLOW -> {
                list.addLine(cx - h * 0.8f, cy, cx + h * 0.5f, cy, color, thickness)
                list.addTriangleFilled(
                    cx + h * 0.8f,
                    cy,
                    cx + h * 0.35f,
                    cy - h * 0.4f,
                    cx + h * 0.35f,
                    cy + h * 0.4f,
                    color
                )
                list.addLine(cx - h * 0.8f, cy - h * 0.6f, cx - h * 0.8f, cy + h * 0.6f, color, thickness)
            }

            Icon.TRASH -> {
                list.addRect(cx - h * 0.5f, cy - h * 0.35f, cx + h * 0.5f, cy + h * 0.75f, color, 1f, 0, thickness)
                list.addLine(cx - h * 0.7f, cy - h * 0.35f, cx + h * 0.7f, cy - h * 0.35f, color, thickness)
                list.addLine(cx - h * 0.25f, cy - h * 0.65f, cx + h * 0.25f, cy - h * 0.65f, color, thickness)
            }

            Icon.EXPORT -> {
                list.addLine(cx, cy + h * 0.3f, cx, cy - h * 0.8f, color, thickness)
                list.addLine(cx, cy - h * 0.8f, cx - h * 0.4f, cy - h * 0.4f, color, thickness)
                list.addLine(cx, cy - h * 0.8f, cx + h * 0.4f, cy - h * 0.4f, color, thickness)
                list.addLine(cx - h * 0.7f, cy + h * 0.1f, cx - h * 0.7f, cy + h * 0.75f, color, thickness)
                list.addLine(cx - h * 0.7f, cy + h * 0.75f, cx + h * 0.7f, cy + h * 0.75f, color, thickness)
                list.addLine(cx + h * 0.7f, cy + h * 0.75f, cx + h * 0.7f, cy + h * 0.1f, color, thickness)
            }

            Icon.FOLDER -> {
                list.addRectFilled(cx - h * 0.8f, cy - h * 0.55f, cx - h * 0.1f, cy - h * 0.3f, color, 1f)
                list.addRectFilled(cx - h * 0.8f, cy - h * 0.35f, cx + h * 0.8f, cy + h * 0.6f, color, 1.5f)
            }

            Icon.FILM -> {
                list.addRect(cx - h * 0.8f, cy - h * 0.6f, cx + h * 0.8f, cy + h * 0.6f, color, 1f, 0, thickness)
                for (i in 0 until 4) {
                    val px = cx - h * 0.6f + i * h * 0.4f
                    list.addRectFilled(px - h * 0.08f, cy - h * 0.55f, px + h * 0.08f, cy - h * 0.35f, color)
                    list.addRectFilled(px - h * 0.08f, cy + h * 0.35f, px + h * 0.08f, cy + h * 0.55f, color)
                }
            }

            Icon.USER -> {
                list.addCircleFilled(cx, cy - h * 0.35f, h * 0.3f, color, 14)
                list.addBezierQuadratic(
                    cx - h * 0.7f,
                    cy + h * 0.75f,
                    cx,
                    cy - h * 0.1f,
                    cx + h * 0.7f,
                    cy + h * 0.75f,
                    color,
                    thickness * 1.5f,
                    10
                )
            }

            Icon.LINK -> {
                list.addRect(
                    cx - h * 0.8f,
                    cy - h * 0.25f,
                    cx - h * 0.05f,
                    cy + h * 0.25f,
                    color,
                    h * 0.25f,
                    0,
                    thickness
                )
                list.addRect(
                    cx + h * 0.05f,
                    cy - h * 0.25f,
                    cx + h * 0.8f,
                    cy + h * 0.25f,
                    color,
                    h * 0.25f,
                    0,
                    thickness
                )
                list.addLine(cx - h * 0.3f, cy, cx + h * 0.3f, cy, color, thickness)
            }

            Icon.CHECK -> {
                list.addLine(cx - h * 0.6f, cy, cx - h * 0.15f, cy + h * 0.45f, color, thickness * 1.4f)
                list.addLine(cx - h * 0.15f, cy + h * 0.45f, cx + h * 0.65f, cy - h * 0.5f, color, thickness * 1.4f)
            }

            Icon.WARNING -> {
                list.addTriangle(
                    cx,
                    cy - h * 0.75f,
                    cx - h * 0.8f,
                    cy + h * 0.65f,
                    cx + h * 0.8f,
                    cy + h * 0.65f,
                    color,
                    thickness
                )
                list.addLine(cx, cy - h * 0.25f, cx, cy + h * 0.2f, color, thickness * 1.4f)
                list.addCircleFilled(cx, cy + h * 0.45f, thickness * 0.8f, color, 6)
            }

            Icon.INFO -> {
                list.addCircle(cx, cy, h * 0.7f, color, 20, thickness)
                list.addLine(cx, cy - h * 0.1f, cx, cy + h * 0.4f, color, thickness * 1.4f)
                list.addCircleFilled(cx, cy - h * 0.35f, thickness * 0.8f, color, 6)
            }

            Icon.SAVE -> {
                list.addRect(cx - h * 0.7f, cy - h * 0.7f, cx + h * 0.7f, cy + h * 0.7f, color, 2f, 0, thickness)
                list.addRectFilled(cx - h * 0.4f, cy - h * 0.7f, cx + h * 0.3f, cy - h * 0.25f, color)
                list.addRect(cx - h * 0.4f, cy + h * 0.1f, cx + h * 0.4f, cy + h * 0.7f, color, 1f, 0, thickness)
            }

            Icon.UNDO -> {
                list.addBezierQuadratic(
                    cx - h * 0.5f,
                    cy - h * 0.1f,
                    cx + h * 0.2f,
                    cy - h * 0.9f,
                    cx + h * 0.6f,
                    cy + h * 0.1f,
                    color,
                    thickness,
                    12
                )
                list.addBezierQuadratic(
                    cx + h * 0.6f,
                    cy + h * 0.1f,
                    cx + h * 0.4f,
                    cy + h * 0.7f,
                    cx - h * 0.2f,
                    cy + h * 0.65f,
                    color,
                    thickness,
                    12
                )
                list.addTriangleFilled(
                    cx - h * 0.85f,
                    cy - h * 0.1f,
                    cx - h * 0.3f,
                    cy - h * 0.45f,
                    cx - h * 0.3f,
                    cy + h * 0.25f,
                    color
                )
            }

            Icon.REDO -> {
                list.addBezierQuadratic(
                    cx + h * 0.5f,
                    cy - h * 0.1f,
                    cx - h * 0.2f,
                    cy - h * 0.9f,
                    cx - h * 0.6f,
                    cy + h * 0.1f,
                    color,
                    thickness,
                    12
                )
                list.addBezierQuadratic(
                    cx - h * 0.6f,
                    cy + h * 0.1f,
                    cx - h * 0.4f,
                    cy + h * 0.7f,
                    cx + h * 0.2f,
                    cy + h * 0.65f,
                    color,
                    thickness,
                    12
                )
                list.addTriangleFilled(
                    cx + h * 0.85f,
                    cy - h * 0.1f,
                    cx + h * 0.3f,
                    cy - h * 0.45f,
                    cx + h * 0.3f,
                    cy + h * 0.25f,
                    color
                )
            }

            Icon.COPY -> {
                list.addRect(cx - h * 0.75f, cy - h * 0.75f, cx + h * 0.2f, cy + h * 0.2f, color, 1.5f, 0, thickness)
                list.addRect(cx - h * 0.2f, cy - h * 0.2f, cx + h * 0.75f, cy + h * 0.75f, color, 1.5f, 0, thickness)
            }

            Icon.PASTE -> {
                list.addRect(cx - h * 0.6f, cy - h * 0.55f, cx + h * 0.6f, cy + h * 0.75f, color, 1.5f, 0, thickness)
                list.addRectFilled(cx - h * 0.3f, cy - h * 0.8f, cx + h * 0.3f, cy - h * 0.4f, color, 1f)
                list.addLine(cx - h * 0.3f, cy + h * 0.05f, cx + h * 0.3f, cy + h * 0.05f, color, thickness)
                list.addLine(cx - h * 0.3f, cy + h * 0.35f, cx + h * 0.1f, cy + h * 0.35f, color, thickness)
            }

            Icon.SCISSORS -> {
                list.addCircle(cx - h * 0.45f, cy + h * 0.45f, h * 0.28f, color, 12, thickness)
                list.addCircle(cx + h * 0.45f, cy + h * 0.45f, h * 0.28f, color, 12, thickness)
                list.addLine(cx - h * 0.25f, cy + h * 0.25f, cx + h * 0.55f, cy - h * 0.8f, color, thickness)
                list.addLine(cx + h * 0.25f, cy + h * 0.25f, cx - h * 0.55f, cy - h * 0.8f, color, thickness)
            }

            Icon.ARROW_RIGHT -> {
                list.addLine(cx - h * 0.75f, cy, cx + h * 0.65f, cy, color, thickness)
                list.addLine(cx + h * 0.65f, cy, cx + h * 0.15f, cy - h * 0.5f, color, thickness)
                list.addLine(cx + h * 0.65f, cy, cx + h * 0.15f, cy + h * 0.5f, color, thickness)
            }

            Icon.ARROW_LEFT -> {
                list.addLine(cx + h * 0.75f, cy, cx - h * 0.65f, cy, color, thickness)
                list.addLine(cx - h * 0.65f, cy, cx - h * 0.15f, cy - h * 0.5f, color, thickness)
                list.addLine(cx - h * 0.65f, cy, cx - h * 0.15f, cy + h * 0.5f, color, thickness)
            }

            Icon.CLOCK -> {
                list.addCircle(cx, cy, h * 0.75f, color, 24, thickness)
                list.addLine(cx, cy - h * 0.45f, cx, cy, color, thickness)
                list.addLine(cx, cy, cx + h * 0.35f, cy + h * 0.2f, color, thickness)
            }

            Icon.SUN -> {
                list.addCircle(cx, cy, h * 0.35f, color, 16, thickness)
                for (i in 0 until 8) {
                    val angle = i * Math.PI / 4.0
                    val dx = cos(angle).toFloat()
                    val dy = sin(angle).toFloat()
                    list.addLine(
                        cx + dx * h * 0.55f,
                        cy + dy * h * 0.55f,
                        cx + dx * h * 0.85f,
                        cy + dy * h * 0.85f,
                        color,
                        thickness
                    )
                }
            }

            Icon.GAUGE -> {
                list.addBezierQuadratic(
                    cx - h * 0.8f,
                    cy + h * 0.45f,
                    cx,
                    cy - h * 1.2f,
                    cx + h * 0.8f,
                    cy + h * 0.45f,
                    color,
                    thickness,
                    14
                )
                list.addLine(cx, cy + h * 0.45f, cx + h * 0.45f, cy - h * 0.2f, color, thickness * 1.3f)
                list.addCircleFilled(cx, cy + h * 0.45f, thickness, color, 8)
            }

            Icon.VOLUME -> {
                list.addQuadFilled(
                    cx - h * 0.8f,
                    cy - h * 0.25f,
                    cx - h * 0.4f,
                    cy - h * 0.25f,
                    cx,
                    cy - h * 0.65f,
                    cx,
                    cy + h * 0.65f,
                    color
                )
                list.addQuadFilled(
                    cx - h * 0.8f,
                    cy - h * 0.25f,
                    cx - h * 0.8f,
                    cy + h * 0.25f,
                    cx - h * 0.4f,
                    cy + h * 0.25f,
                    cx,
                    cy + h * 0.65f,
                    color
                )
                list.addBezierQuadratic(
                    cx + h * 0.25f,
                    cy - h * 0.35f,
                    cx + h * 0.55f,
                    cy,
                    cx + h * 0.25f,
                    cy + h * 0.35f,
                    color,
                    thickness,
                    8
                )
                list.addBezierQuadratic(
                    cx + h * 0.45f,
                    cy - h * 0.65f,
                    cx + h * 0.95f,
                    cy,
                    cx + h * 0.45f,
                    cy + h * 0.65f,
                    color,
                    thickness,
                    8
                )
            }

            Icon.LAYERS -> {
                list.addQuad(
                    cx,
                    cy - h * 0.8f,
                    cx + h * 0.8f,
                    cy - h * 0.35f,
                    cx,
                    cy + h * 0.1f,
                    cx - h * 0.8f,
                    cy - h * 0.35f,
                    color,
                    thickness
                )
                list.addLine(cx - h * 0.8f, cy + h * 0.05f, cx, cy + h * 0.5f, color, thickness)
                list.addLine(cx, cy + h * 0.5f, cx + h * 0.8f, cy + h * 0.05f, color, thickness)
                list.addLine(cx - h * 0.8f, cy + h * 0.4f, cx, cy + h * 0.85f, color, thickness)
                list.addLine(cx, cy + h * 0.85f, cx + h * 0.8f, cy + h * 0.4f, color, thickness)
            }

            Icon.PATH -> {
                list.addBezierCubic(
                    cx - h * 0.8f,
                    cy + h * 0.6f,
                    cx - h * 0.5f,
                    cy - h * 1.1f,
                    cx + h * 0.5f,
                    cy + h * 1.1f,
                    cx + h * 0.8f,
                    cy - h * 0.6f,
                    color,
                    thickness,
                    16
                )
                diamond(list, cx - h * 0.8f, cy + h * 0.6f, h * 0.22f, color, true)
                diamond(list, cx + h * 0.8f, cy - h * 0.6f, h * 0.22f, color, true)
            }

            Icon.TARGET -> {
                list.addCircle(cx, cy, h * 0.7f, color, 24, thickness)
                list.addCircleFilled(cx, cy, h * 0.18f, color, 10)
                list.addLine(cx - h * 0.95f, cy, cx - h * 0.45f, cy, color, thickness)
                list.addLine(cx + h * 0.45f, cy, cx + h * 0.95f, cy, color, thickness)
                list.addLine(cx, cy - h * 0.95f, cx, cy - h * 0.45f, color, thickness)
                list.addLine(cx, cy + h * 0.45f, cx, cy + h * 0.95f, color, thickness)
            }

            Icon.ORBIT -> {
                list.addCircleFilled(cx, cy, h * 0.25f, color, 12)
                list.addCircle(cx, cy, h * 0.75f, color, 24, thickness)
                list.addCircleFilled(cx + h * 0.53f, cy - h * 0.53f, h * 0.16f, color, 8)
            }

            Icon.PERSON -> {
                list.addCircle(cx, cy - h * 0.4f, h * 0.28f, color, 14, thickness)
                list.addBezierQuadratic(
                    cx - h * 0.65f,
                    cy + h * 0.8f,
                    cx,
                    cy - h * 0.05f,
                    cx + h * 0.65f,
                    cy + h * 0.8f,
                    color,
                    thickness,
                    10
                )
            }

            Icon.GRID -> {
                for (i in 0 until 3) {
                    val p = cy - h * 0.6f + i * h * 0.6f
                    list.addLine(cx - h * 0.7f, p, cx + h * 0.7f, p, color, thickness)
                    val q = cx - h * 0.6f + i * h * 0.6f
                    list.addLine(q, cy - h * 0.7f, q, cy + h * 0.7f, color, thickness)
                }
            }

            Icon.MORE -> {
                list.addCircleFilled(cx - h * 0.55f, cy, thickness * 1.1f, color, 8)
                list.addCircleFilled(cx, cy, thickness * 1.1f, color, 8)
                list.addCircleFilled(cx + h * 0.55f, cy, thickness * 1.1f, color, 8)
            }

            Icon.REFRESH -> {
                list.addBezierCubic(
                    cx + h * 0.55f,
                    cy - h * 0.45f,
                    cx + h * 0.1f,
                    cy - h * 1.05f,
                    cx - h * 0.9f,
                    cy - h * 0.4f,
                    cx - h * 0.7f,
                    cy + h * 0.25f,
                    color,
                    thickness,
                    14
                )
                list.addBezierCubic(
                    cx - h * 0.55f,
                    cy + h * 0.45f,
                    cx - h * 0.1f,
                    cy + h * 1.05f,
                    cx + h * 0.9f,
                    cy + h * 0.4f,
                    cx + h * 0.7f,
                    cy - h * 0.25f,
                    color,
                    thickness,
                    14
                )
                list.addTriangleFilled(
                    cx + h * 0.75f,
                    cy - h * 0.8f,
                    cx + h * 0.8f,
                    cy - h * 0.2f,
                    cx + h * 0.25f,
                    cy - h * 0.35f,
                    color
                )
                list.addTriangleFilled(
                    cx - h * 0.75f,
                    cy + h * 0.8f,
                    cx - h * 0.8f,
                    cy + h * 0.2f,
                    cx - h * 0.25f,
                    cy + h * 0.35f,
                    color
                )
            }

            Icon.COMPRESS -> {
                list.addLine(cx - h * 0.8f, cy - h * 0.8f, cx - h * 0.2f, cy - h * 0.2f, color, thickness)
                list.addLine(cx - h * 0.2f, cy - h * 0.2f, cx - h * 0.65f, cy - h * 0.2f, color, thickness)
                list.addLine(cx - h * 0.2f, cy - h * 0.2f, cx - h * 0.2f, cy - h * 0.65f, color, thickness)
                list.addLine(cx + h * 0.8f, cy + h * 0.8f, cx + h * 0.2f, cy + h * 0.2f, color, thickness)
                list.addLine(cx + h * 0.2f, cy + h * 0.2f, cx + h * 0.65f, cy + h * 0.2f, color, thickness)
                list.addLine(cx + h * 0.2f, cy + h * 0.2f, cx + h * 0.2f, cy + h * 0.65f, color, thickness)
            }

            Icon.HOME -> {
                list.addLine(cx - h * 0.85f, cy, cx, cy - h * 0.8f, color, thickness)
                list.addLine(cx, cy - h * 0.8f, cx + h * 0.85f, cy, color, thickness)
                list.addRect(cx - h * 0.6f, cy - h * 0.15f, cx + h * 0.6f, cy + h * 0.75f, color, 0f, 0, thickness)
            }

            Icon.EDIT -> {
                list.addLine(cx - h * 0.7f, cy + h * 0.7f, cx + h * 0.4f, cy - h * 0.4f, color, thickness * 1.3f)
                list.addLine(cx + h * 0.4f, cy - h * 0.4f, cx + h * 0.7f, cy - h * 0.7f, color, thickness * 2.2f)
            }

            Icon.TAG -> {
                list.addQuad(
                    cx - h * 0.8f,
                    cy - h * 0.6f,
                    cx + h * 0.1f,
                    cy - h * 0.6f,
                    cx + h * 0.8f,
                    cy + h * 0.1f,
                    cx + h * 0.1f,
                    cy + h * 0.8f,
                    color,
                    thickness
                )
                list.addLine(cx + h * 0.1f, cy + h * 0.8f, cx - h * 0.8f, cy - h * 0.1f, color, thickness)
                list.addCircleFilled(cx - h * 0.45f, cy - h * 0.3f, thickness * 0.9f, color, 8)
            }

            Icon.DOT -> list.addCircleFilled(cx, cy, h * 0.25f, color, 12)
            Icon.IMAGE -> {
                list.addRect(cx - h * 0.8f, cy - h * 0.65f, cx + h * 0.8f, cy + h * 0.65f, color, 1.5f, 0, thickness)
                list.addCircleFilled(cx - h * 0.4f, cy - h * 0.25f, h * 0.14f, color, 8)
                list.addLine(cx - h * 0.7f, cy + h * 0.5f, cx - h * 0.1f, cy - h * 0.1f, color, thickness)
                list.addLine(cx - h * 0.1f, cy - h * 0.1f, cx + h * 0.3f, cy + h * 0.3f, color, thickness)
                list.addLine(cx + h * 0.3f, cy + h * 0.3f, cx + h * 0.7f, cy - h * 0.1f, color, thickness)
            }

            Icon.COMMAND -> {
                list.addRect(cx - h * 0.35f, cy - h * 0.35f, cx + h * 0.35f, cy + h * 0.35f, color, 0f, 0, thickness)
                list.addCircle(cx - h * 0.55f, cy - h * 0.55f, h * 0.22f, color, 12, thickness)
                list.addCircle(cx + h * 0.55f, cy - h * 0.55f, h * 0.22f, color, 12, thickness)
                list.addCircle(cx - h * 0.55f, cy + h * 0.55f, h * 0.22f, color, 12, thickness)
                list.addCircle(cx + h * 0.55f, cy + h * 0.55f, h * 0.22f, color, 12, thickness)
            }

            Icon.SLIDERS -> {
                for (i in 0 until 3) {
                    val p = cy - h * 0.55f + i * h * 0.55f
                    list.addLine(cx - h * 0.8f, p, cx + h * 0.8f, p, color, thickness)
                    val knob = cx + (if (i == 1) h * 0.35f else -h * 0.3f)
                    list.addCircleFilled(knob, p, h * 0.16f, color, 10)
                }
            }

            Icon.PIN -> {
                list.addLine(cx, cy + h * 0.2f, cx, cy + h * 0.85f, color, thickness)
                list.addTriangleFilled(
                    cx - h * 0.55f,
                    cy + h * 0.2f,
                    cx + h * 0.55f,
                    cy + h * 0.2f,
                    cx,
                    cy - h * 0.2f,
                    color
                )
                list.addRectFilled(cx - h * 0.35f, cy - h * 0.8f, cx + h * 0.35f, cy - h * 0.2f, color, 1f)
            }

            Icon.BOOKMARK -> {
                list.addLine(cx - h * 0.55f, cy - h * 0.8f, cx + h * 0.55f, cy - h * 0.8f, color, thickness)
                list.addLine(cx - h * 0.55f, cy - h * 0.8f, cx - h * 0.55f, cy + h * 0.8f, color, thickness)
                list.addLine(cx + h * 0.55f, cy - h * 0.8f, cx + h * 0.55f, cy + h * 0.8f, color, thickness)
                list.addLine(cx - h * 0.55f, cy + h * 0.8f, cx, cy + h * 0.35f, color, thickness)
                list.addLine(cx + h * 0.55f, cy + h * 0.8f, cx, cy + h * 0.35f, color, thickness)
            }

            Icon.TOOL_VIEW -> {
                list.pathLineTo(cx - h * 0.55f, cy - h * 0.8f)
                list.pathLineTo(cx - h * 0.55f, cy + h * 0.45f)
                list.pathLineTo(cx - h * 0.2f, cy + h * 0.15f)
                list.pathLineTo(cx + h * 0.05f, cy + h * 0.75f)
                list.pathLineTo(cx + h * 0.35f, cy + h * 0.6f)
                list.pathLineTo(cx + h * 0.1f, cy + h * 0.05f)
                list.pathLineTo(cx + h * 0.55f, cy + h * 0.0f)
                list.pathFillConvex(color)
            }

            Icon.TOOL_MOVE -> {
                list.addLine(cx - h * 0.8f, cy, cx + h * 0.8f, cy, color, thickness)
                list.addLine(cx, cy - h * 0.8f, cx, cy + h * 0.8f, color, thickness)
                list.addTriangleFilled(
                    cx - h * 0.95f,
                    cy,
                    cx - h * 0.55f,
                    cy - h * 0.3f,
                    cx - h * 0.55f,
                    cy + h * 0.3f,
                    color
                )
                list.addTriangleFilled(
                    cx + h * 0.95f,
                    cy,
                    cx + h * 0.55f,
                    cy - h * 0.3f,
                    cx + h * 0.55f,
                    cy + h * 0.3f,
                    color
                )
                list.addTriangleFilled(
                    cx,
                    cy - h * 0.95f,
                    cx - h * 0.3f,
                    cy - h * 0.55f,
                    cx + h * 0.3f,
                    cy - h * 0.55f,
                    color
                )
                list.addTriangleFilled(
                    cx,
                    cy + h * 0.95f,
                    cx - h * 0.3f,
                    cy + h * 0.55f,
                    cx + h * 0.3f,
                    cy + h * 0.55f,
                    color
                )
            }

            Icon.TOOL_ROTATE -> {
                list.pathArcTo(cx, cy, h * 0.68f, (Math.PI * 0.35).toFloat(), (Math.PI * 1.85).toFloat(), 20)
                list.pathStroke(color, 0, thickness * 1.2f)
                val ax = cx + h * 0.68f * cos(Math.PI * 1.85).toFloat()
                val ay = cy + h * 0.68f * sin(Math.PI * 1.85).toFloat()
                list.addTriangleFilled(
                    ax + h * 0.32f,
                    ay - h * 0.02f,
                    ax - h * 0.22f,
                    ay - h * 0.3f,
                    ax - h * 0.1f,
                    ay + h * 0.32f,
                    color
                )
            }

            Icon.TOOL_SCALE -> {
                list.addLine(cx - h * 0.35f, cy + h * 0.35f, cx + h * 0.55f, cy - h * 0.55f, color, thickness)
                list.addTriangleFilled(
                    cx + h * 0.8f,
                    cy - h * 0.8f,
                    cx + h * 0.25f,
                    cy - h * 0.75f,
                    cx + h * 0.75f,
                    cy - h * 0.25f,
                    color
                )
                list.addRect(cx - h * 0.8f, cy - h * 0.1f, cx + h * 0.1f, cy + h * 0.8f, color, 1f, 0, thickness)
            }

            Icon.GLOBE -> {
                list.addCircle(cx, cy, h * 0.75f, color, 24, thickness)
                list.addLine(cx - h * 0.75f, cy, cx + h * 0.75f, cy, color, thickness)
                list.addLine(cx, cy - h * 0.75f, cx, cy + h * 0.75f, color, thickness)
                list.addBezierQuadratic(
                    cx,
                    cy - h * 0.75f,
                    cx + h * 0.55f,
                    cy,
                    cx,
                    cy + h * 0.75f,
                    color,
                    thickness,
                    12
                )
                list.addBezierQuadratic(
                    cx,
                    cy - h * 0.75f,
                    cx - h * 0.55f,
                    cy,
                    cx,
                    cy + h * 0.75f,
                    color,
                    thickness,
                    12
                )
            }

            Icon.CUBE -> {
                val dx = h * 0.7f
                val dy = h * 0.38f
                list.addLine(cx, cy - h * 0.8f, cx + dx, cy - dy, color, thickness)
                list.addLine(cx + dx, cy - dy, cx + dx, cy + dy, color, thickness)
                list.addLine(cx + dx, cy + dy, cx, cy + h * 0.8f, color, thickness)
                list.addLine(cx, cy + h * 0.8f, cx - dx, cy + dy, color, thickness)
                list.addLine(cx - dx, cy + dy, cx - dx, cy - dy, color, thickness)
                list.addLine(cx - dx, cy - dy, cx, cy - h * 0.8f, color, thickness)
                list.addLine(cx - dx, cy - dy, cx, cy, color, thickness)
                list.addLine(cx + dx, cy - dy, cx, cy, color, thickness)
                list.addLine(cx, cy, cx, cy + h * 0.8f, color, thickness)
            }

            Icon.LIST -> {
                for (i in 0 until 3) {
                    val p = cy - h * 0.55f + i * h * 0.55f
                    val indent = if (i == 0) -h * 0.75f else -h * 0.35f
                    list.addCircleFilled(cx + indent, p, thickness * 0.9f, color, 8)
                    list.addLine(cx + indent + h * 0.28f, p, cx + h * 0.8f, p, color, thickness)
                }
            }

            Icon.CHEVRON_LEFT -> {
                list.addLine(cx + h * 0.25f, cy - h * 0.5f, cx - h * 0.25f, cy, color, thickness)
                list.addLine(cx - h * 0.25f, cy, cx + h * 0.25f, cy + h * 0.5f, color, thickness)
            }

            Icon.WAVE -> {
                list.addBezierCubic(
                    cx - h * 0.85f,
                    cy,
                    cx - h * 0.45f,
                    cy - h * 1.3f,
                    cx - h * 0.05f,
                    cy + h * 1.3f,
                    cx + h * 0.35f,
                    cy,
                    color,
                    thickness,
                    14
                )
                list.addBezierQuadratic(
                    cx + h * 0.35f,
                    cy,
                    cx + h * 0.6f,
                    cy - h * 0.7f,
                    cx + h * 0.85f,
                    cy,
                    color,
                    thickness,
                    10
                )
            }

            Icon.APERTURE -> {
                list.addCircle(cx, cy, h * 0.75f, color, 24, thickness)
                for (i in 0 until 6) {
                    val angle = i * Math.PI / 3.0
                    val ax = cx + h * 0.75f * cos(angle).toFloat()
                    val ay = cy + h * 0.75f * sin(angle).toFloat()
                    val bx = cx + h * 0.25f * cos(angle + Math.PI / 3.0 * 2.0).toFloat()
                    val by = cy + h * 0.25f * sin(angle + Math.PI / 3.0 * 2.0).toFloat()
                    list.addLine(ax, ay, bx, by, color, thickness)
                }
            }

            Icon.SIDEBAR -> {
                list.addRect(cx - h * 0.8f, cy - h * 0.6f, cx + h * 0.8f, cy + h * 0.6f, color, 1.5f, 0, thickness)
                list.addLine(cx - h * 0.3f, cy - h * 0.6f, cx - h * 0.3f, cy + h * 0.6f, color, thickness)
            }

            Icon.MONITOR -> {
                list.addRect(cx - h * 0.8f, cy - h * 0.65f, cx + h * 0.8f, cy + h * 0.4f, color, 1.5f, 0, thickness)
                list.addLine(cx - h * 0.3f, cy + h * 0.75f, cx + h * 0.3f, cy + h * 0.75f, color, thickness)
                list.addLine(cx, cy + h * 0.4f, cx, cy + h * 0.75f, color, thickness)
            }

            Icon.SNOWFLAKE -> {
                for (i in 0 until 3) {
                    val angle = i * Math.PI / 3.0
                    val dx = cos(angle).toFloat() * h * 0.8f
                    val dy = sin(angle).toFloat() * h * 0.8f
                    list.addLine(cx - dx, cy - dy, cx + dx, cy + dy, color, thickness)
                }
                list.addCircleFilled(cx, cy, thickness, color, 8)
            }

            Icon.ARROW_UP -> {
                list.addLine(cx, cy + h * 0.75f, cx, cy - h * 0.65f, color, thickness)
                list.addLine(cx, cy - h * 0.65f, cx - h * 0.5f, cy - h * 0.15f, color, thickness)
                list.addLine(cx, cy - h * 0.65f, cx + h * 0.5f, cy - h * 0.15f, color, thickness)
            }

            Icon.ARROW_DOWN -> {
                list.addLine(cx, cy - h * 0.75f, cx, cy + h * 0.65f, color, thickness)
                list.addLine(cx, cy + h * 0.65f, cx - h * 0.5f, cy + h * 0.15f, color, thickness)
                list.addLine(cx, cy + h * 0.65f, cx + h * 0.5f, cy + h * 0.15f, color, thickness)
            }

            Icon.CIRCLE -> list.addCircle(cx, cy, h * 0.6f, color, 20, thickness)

            Icon.GRAPH -> {
                list.addLine(cx - h * 0.8f, cy - h * 0.8f, cx - h * 0.8f, cy + h * 0.8f, color, thickness)
                list.addLine(cx - h * 0.8f, cy + h * 0.8f, cx + h * 0.8f, cy + h * 0.8f, color, thickness)
                list.addBezierCubic(
                    cx - h * 0.6f, cy + h * 0.55f,
                    cx - h * 0.1f, cy + h * 0.55f,
                    cx + h * 0.1f, cy - h * 0.7f,
                    cx + h * 0.75f, cy - h * 0.65f,
                    color, thickness, 12
                )
                list.addCircleFilled(cx - h * 0.6f, cy + h * 0.55f, thickness * 1.1f, color, 8)
                list.addCircleFilled(cx + h * 0.75f, cy - h * 0.65f, thickness * 1.1f, color, 8)
            }

            Icon.AUTO_KEY -> {
                list.addCircle(cx, cy, h * 0.75f, color, 20, thickness)
                diamond(list, cx, cy, h * 0.38f, color, true)
            }
        }
    }

    fun diamond(
        list: ImDrawList,
        cx: Float,
        cy: Float,
        radius: Float,
        color: Int,
        filled: Boolean,
        thickness: Float = 1.5f
    ) {
        if (filled) list.addQuadFilled(cx, cy - radius, cx + radius, cy, cx, cy + radius, cx - radius, cy, color)
        else list.addQuad(cx, cy - radius, cx + radius, cy, cx, cy + radius, cx - radius, cy, color, thickness)
    }

    fun inline(icon: Icon, size: Float = ImGui.getFontSize().toFloat(), color: Int = EditorTheme.TEXT.u32) {
        val x = ImGui.getCursorScreenPosX()
        val y = ImGui.getCursorScreenPosY()
        draw(ImGui.getWindowDrawList(), icon, x, y, size, color)
        ImGui.dummy(size, size)
    }

    private fun corner(list: ImDrawList, x: Float, y: Float, dx: Float, dy: Float, color: Int, thickness: Float) {
        list.addLine(x, y, x + dx, y, color, thickness)
        list.addLine(x, y, x, y + dy, color, thickness)
    }
}
