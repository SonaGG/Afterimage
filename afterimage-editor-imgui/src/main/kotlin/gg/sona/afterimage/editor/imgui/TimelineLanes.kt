package gg.sona.afterimage.editor.imgui

import gg.sona.afterimage.camera.CameraKeyframe
import gg.sona.afterimage.camera.CameraMode
import gg.sona.afterimage.camera.CameraSettings
import gg.sona.afterimage.camera.track.Extrapolation
import gg.sona.afterimage.camera.track.Keyframe
import gg.sona.afterimage.camera.track.SegmentMode
import gg.sona.afterimage.clip.Clip
import gg.sona.afterimage.core.time.Nanos
import gg.sona.afterimage.editor.*
import imgui.ImDrawList
import imgui.ImGui
import imgui.flag.ImGuiMouseCursor
import java.util.*
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sin

class TimelineLanes(
    private val context: EditorContext,
    private val geometry: TimelineGeometry,
    private val cache: TimelineCache,
    private val gameLanes: GameLanes,
) {
    var hovered: TimelineItem? = null
    var trimClip: UUID? = null
    var trimStart = 0L
    var trimEnd = 0L
    var duplicating = false

    fun draw(list: ImDrawList, session: EditorSession, nowNanos: Long) {
        for (lane in geometry.lanes) {
            if (!geometry.laneVisible(lane.kind)) continue
            val top = geometry.laneTop(lane.kind)
            when (lane.kind) {
                LaneKind.CAMERA -> camera(list, session, lane, nowNanos)
                LaneKind.VIEW -> holds(list, session, lane, cache.viewKeys, session.selection.viewTimes, { TimelineItem.ViewKey(it.timeNanos) }) { viewLabel(it.value) }
                LaneKind.TEXTURE_PACK -> holds(list, session, lane, cache.packKeys, session.selection.packTimes, { TimelineItem.PackKey(it.timeNanos) }) { packLabel(it.value) }
                LaneKind.CLIPS -> clips(list, session, lane)
                LaneKind.MARKERS -> markers(list, session, lane)
                LaneKind.TIMELAPSE -> timelapses(list, session, lane)
                LaneKind.EVENTS -> events(list, session, lane)
                LaneKind.PLAYERS -> gameLanes.drawPlayers(list, top, lane.rowHeight)
                LaneKind.WORLD -> gameLanes.drawWorld(list, top, lane.rowHeight)
                LaneKind.MOMENTS -> gameLanes.drawMoments(list, session, top, geometry.laneHeight(lane.kind))
                else -> lane.valueLane?.let { value(list, session, lane, it) }
            }
        }
    }

    fun drawGuides(list: ImDrawList, session: EditorSession) {
        if (geometry.lanes.none { it.kind == LaneKind.MARKERS }) return
        val selection = session.selection.markerIds
        for (marker in cache.markers) {
            val x = geometry.xAt(geometry.shown(marker.nanos, marker.id in selection))
            if (x < geometry.originX || x > geometry.right) continue
            list.addLine(x, geometry.tracksTop, x, geometry.tracksBottom, Widgets.rgbToU32(marker.color, 0.16f), 1f)
        }
    }

    private fun camera(list: ImDrawList, session: EditorSession, lane: TrackSpec, nowNanos: Long) {
        val top = geometry.laneTop(lane.kind)
        val height = geometry.laneHeight(lane.kind)
        val cy = top + height / 2f
        val selection = session.selection.keyframeTimes
        val muted = session.project.lane(LaneKind.CAMERA).muted
        val alpha = if (muted) 0.4f else 1f
        val keyframes = cache.keyframes
        if (keyframes.isEmpty()) {
            EditorFonts.with(EditorFonts.small) {
                list.addText(
                    geometry.originX + EditorFonts.px(12f),
                    cy - ImGui.getFontSize() / 2f,
                    EditorTheme.TEXT_DIM.u32(0.7f),
                    "No camera keyframes yet. Move the camera and press Ctrl+K to key it at the playhead."
                )
            }
            return
        }
        for (index in 0 until keyframes.size - 1) {
            val from = keyframes[index]
            val to = keyframes[index + 1]
            val x1 = geometry.xAt(geometry.shown(from.timeNanos, from.timeNanos in selection))
            val x2 = geometry.xAt(geometry.shown(to.timeNanos, to.timeNanos in selection))
            if (x2 < geometry.originX || x1 > geometry.right || x2 <= x1) continue
            val color = EditorTheme.modeColor(from.mode).u32(0.55f * alpha)
            when {
                from.mode == SegmentMode.HOLD -> {
                    list.addLine(x1, cy, x2, cy, EditorTheme.KEYFRAME_HOLD.u32(0.35f * alpha), 2f)
                    list.addLine(x2 - 1f, cy - EditorFonts.px(4f), x2 - 1f, cy + EditorFonts.px(4f), color, 1.5f)
                }

                from.easing.isLinear || x2 - x1 < EASE_MIN_WIDTH -> list.addLine(x1, cy, x2, cy, color, 2f)
                else -> {
                    val rise = height * 0.26f
                    val steps = minOf(48, ((x2 - x1) / 4f).toInt().coerceAtLeast(8))
                    var previousX = x1
                    var previousY = cy + rise
                    for (step in 1..steps) {
                        val t = step.toDouble() / steps
                        val x = x1 + (x2 - x1) * t.toFloat()
                        val y = cy + rise - rise * 2f * from.easing.clamped(t).toFloat()
                        list.addLine(previousX, previousY, x, y, color, 2f)
                        previousX = x
                        previousY = y
                    }
                }
            }
        }
        val playhead = session.playheadNanos
        val pulse = 0.5f + 0.5f * sin(nowNanos / 2.2e8).toFloat()
        for (frame in keyframes) {
            val selected = frame.timeNanos in selection
            val x = geometry.xAt(geometry.shown(frame.timeNanos, selected))
            if (x < geometry.originX - KEY_RADIUS || x > geometry.right + KEY_RADIUS) continue
            if (duplicating && selected) glyph(list, geometry.xAt(frame.timeNanos), cy, KEY_RADIUS, frame.mode, EditorTheme.KEYFRAME_SMOOTH.u32(0.3f), 0, 0f)
            val atPlayhead = abs(frame.timeNanos - playhead) < Nanos.PER_MILLI * 5
            if (atPlayhead && !muted) list.addCircle(x, cy, KEY_RADIUS + EditorFonts.px(3f) + pulse * 1.5f, EditorTheme.TEXT.u32(0.18f + 0.18f * pulse), 20, 1.5f)
            val hovering = hovered == TimelineItem.CameraKey(frame.timeNanos)
            val radius = KEY_RADIUS + (if (selected) EditorFonts.px(1f) else 0f)
            val fill = when {
                selected -> EditorTheme.SELECTION.u32
                hovering -> EditorTheme.TEXT.u32
                else -> EditorTheme.KEYFRAME_SMOOTH.u32(0.92f * alpha)
            }
            glyph(list, x, cy, radius, frame.mode, fill, if (selected) EditorTheme.TEXT.u32 else EditorTheme.APP_BG.u32(0.85f), if (selected) 1.5f else 1f)
        }
    }

    private fun value(list: ImDrawList, session: EditorSession, spec: TrackSpec, lane: ValueLane) {
        val top = geometry.laneTop(spec.kind)
        val height = geometry.laneHeight(spec.kind)
        val muted = session.project.lane(spec.kind).muted
        val alpha = if (muted) 0.4f else 1f
        val track = session.project.valueTrack(lane)
        val keys = cache.values(lane)
        val selection = session.selection.valueTimes(lane)
        val color = spec.color
        if (keys.isEmpty()) return
        val firstX = geometry.xAt(keys.first().timeNanos)
        val lastX = geometry.xAt(keys.last().timeNanos)
        val holdColor = color.u32(0.22f * alpha)
        if (track.preExtrapolation == Extrapolation.HOLD && firstX > geometry.originX) {
            val y = valueY(lane, top, height, keys.first().value)
            dashed(list, geometry.originX, y, minOf(firstX, geometry.right), y, holdColor)
        }
        if (track.postExtrapolation == Extrapolation.HOLD && lastX < geometry.right) {
            val y = valueY(lane, top, height, keys.last().value)
            dashed(list, maxOf(lastX, geometry.originX), y, geometry.right, y, holdColor)
        }
        if (keys.size >= 2) {
            val startX = maxOf(geometry.originX, firstX)
            val endX = minOf(geometry.right, lastX)
            var x = startX
            var previousY = Float.NaN
            while (x <= endX) {
                val sample = track.valueAt(geometry.nanosAt(x)) ?: lane.default
                val y = valueY(lane, top, height, sample)
                if (!previousY.isNaN()) list.addLine(x - CURVE_STEP, previousY, x, y, color.u32(0.75f * alpha), 1.5f)
                previousY = y
                x += CURVE_STEP
            }
        }
        EditorFonts.with(EditorFonts.small) {
            val fontSize = ImGui.getFontSize()
            for ((index, frame) in keys.withIndex()) {
                val selected = frame.timeNanos in selection
                val x = geometry.xAt(geometry.shown(frame.timeNanos, selected))
                if (x < geometry.originX - 8f || x > geometry.right + 8f) continue
                val y = valueY(lane, top, height, frame.value)
                val hovering = hovered == TimelineItem.ValueKeyItem(ValueKey(lane, frame.timeNanos))
                val fill = when {
                    selected -> EditorTheme.SELECTION.u32
                    hovering -> EditorTheme.TEXT.u32
                    else -> color.u32(alpha)
                }
                glyph(list, x, y, VALUE_RADIUS, frame.mode, fill, if (selected) EditorTheme.TEXT.u32 else EditorTheme.APP_BG.u32(0.85f), if (selected) 1.5f else 1f)
                val label = lane.format(frame.value)
                val labelWidth = Widgets.textWidth(label)
                val nextX = keys.getOrNull(index + 1)?.let { geometry.xAt(geometry.shown(it.timeNanos, it.timeNanos in selection)) } ?: Float.MAX_VALUE
                if (selected || hovering || nextX - x > labelWidth + EditorFonts.px(16f)) {
                    val labelY = (y - fontSize - EditorFonts.px(3f)).coerceIn(top + EditorFonts.px(1f), top + height - fontSize - EditorFonts.px(1f))
                    list.addText(x + EditorFonts.px(8f), labelY, EditorTheme.TEXT_MUTED.u32(alpha), label)
                }
            }
        }
    }

    private fun <T> holds(
        list: ImDrawList,
        session: EditorSession,
        spec: TrackSpec,
        keys: List<Keyframe<T>>,
        selection: Set<Long>,
        itemOf: (Keyframe<T>) -> TimelineItem,
        labelOf: (Keyframe<T>) -> String,
    ) {
        val top = geometry.laneTop(spec.kind)
        val height = geometry.laneHeight(spec.kind)
        val muted = session.project.lane(spec.kind).muted
        val alpha = if (muted) 0.4f else 1f
        val pad = EditorFonts.px(4f)
        for ((index, frame) in keys.withIndex()) {
            val selected = frame.timeNanos in selection
            val time = geometry.shown(frame.timeNanos, selected)
            val next = keys.getOrNull(index + 1)?.let { geometry.shown(it.timeNanos, it.timeNanos in selection) } ?: geometry.duration
            val left = geometry.xAt(time)
            val right = minOf(geometry.xAt(next), geometry.right + 2f)
            if (right < geometry.originX || left > geometry.right) continue
            val hovering = hovered == itemOf(frame)
            val y1 = top + pad
            val y2 = top + height - pad
            val x2 = maxOf(right, left + 3f)
            list.addRectFilled(left, y1, x2, y2, (if (hovering) EditorTheme.CONTROL_HOVER else EditorTheme.CONTROL).u32(0.85f * alpha), EditorFonts.px(3f))
            list.addRectFilled(left, y1, left + EditorFonts.px(3f), y2, spec.color.u32(alpha), EditorFonts.px(2f))
            if (selected) list.addRect(left, y1, x2, y2, EditorTheme.SELECTION.u32, EditorFonts.px(3f), 0, 1.5f)
            if (right - left > EditorFonts.px(30f)) {
                list.pushClipRect(left + EditorFonts.px(5f), y1, right - EditorFonts.px(3f), y2, true)
                EditorFonts.with(EditorFonts.small) {
                    list.addText(left + EditorFonts.px(8f), y1 + (y2 - y1 - ImGui.getFontSize()) / 2f, EditorTheme.TEXT.u32(alpha), labelOf(frame))
                }
                list.popClipRect()
            }
        }
    }

    private fun clips(list: ImDrawList, session: EditorSession, spec: TrackSpec) {
        val top = geometry.laneTop(spec.kind)
        val height = geometry.laneHeight(spec.kind)
        val selection = session.selection.clipIds
        val pad = EditorFonts.px(4f)
        for (clip in cache.clips) {
            val selected = clip.id in selection
            val trimming = trimClip == clip.id
            val start = if (trimming) trimStart else geometry.shown(clip.startNanos, selected)
            val end = if (trimming) trimEnd else geometry.shown(clip.endNanos, selected)
            val left = geometry.xAt(start)
            val right = geometry.xAt(end)
            if (right < geometry.originX || left > geometry.right) continue
            val hovering = hovered == TimelineItem.ClipItem(clip.id)
            val fill = when {
                selected -> EditorTheme.CLIP_SELECTED
                hovering -> EditorTheme.CLIP_HOVER
                else -> EditorTheme.CLIP
            }
            val y1 = top + pad
            val y2 = top + height - pad
            val x2 = maxOf(right, left + 2f)
            list.addRectFilled(left, y1, x2, y2, fill.u32, EditorFonts.px(3f))
            list.addRectFilled(left, y1, x2, y1 + EditorFonts.px(2f), EditorTheme.TEXT.u32(0.16f), EditorFonts.px(2f))
            list.addRect(left, y1, x2, y2, if (selected) EditorTheme.SELECTION.u32 else EditorTheme.APP_BG.u32(0.7f), EditorFonts.px(3f), 0, if (selected) 1.5f else 1f)
            if (right - left > EditorFonts.px(26f)) {
                list.pushClipRect(maxOf(left + 2f, geometry.originX), y1, minOf(right - 2f, geometry.right), y2, true)
                EditorFonts.with(EditorFonts.smallMedium) {
                    list.addText(left + EditorFonts.px(7f), y1 + (y2 - y1 - ImGui.getFontSize()) / 2f, EditorTheme.TEXT.u32, clip.title)
                }
                list.popClipRect()
            }
        }
    }

    private fun markers(list: ImDrawList, session: EditorSession, spec: TrackSpec) {
        val top = geometry.laneTop(spec.kind)
        val height = geometry.laneHeight(spec.kind)
        val selection = session.selection.markerIds
        EditorFonts.with(EditorFonts.small) {
            val fontSize = ImGui.getFontSize()
            val markers = cache.markers
            for ((index, marker) in markers.withIndex()) {
                val selected = marker.id in selection
                val x = geometry.xAt(geometry.shown(marker.nanos, selected))
                if (x < geometry.originX - 12f || x > geometry.right + 12f) continue
                val color = Widgets.rgbToU32(marker.color)
                val hovering = hovered == TimelineItem.MarkerItem(marker.id)
                val flagTop = top + EditorFonts.px(4f)
                val flagBottom = top + height - EditorFonts.px(4f)
                list.addRectFilled(x - 1f, flagTop, x + 1f, flagBottom, color)
                if (marker.kind == MarkerKind.NOTE) list.addTriangleFilled(x + 1f, flagTop, x + EditorFonts.px(9f), flagTop + EditorFonts.px(4f), x + 1f, flagTop + EditorFonts.px(8f), color)
                else Icons.draw(list, MARKER_ICONS[marker.kind] ?: Icon.MARKER, x + 2f, flagTop - 1f, EditorFonts.px(10f), color)
                val nextX = markers.getOrNull(index + 1)?.let { geometry.xAt(geometry.shown(it.nanos, it.id in selection)) } ?: Float.MAX_VALUE
                val available = nextX - x - EditorFonts.px(18f)
                val label = if (available > EditorFonts.px(20f)) Widgets.clip(marker.label, available) else ""
                if (label.isNotEmpty()) list.addText(
                    x + EditorFonts.px(13f),
                    top + (height - fontSize) / 2f,
                    if (hovering || selected) EditorTheme.TEXT.u32 else EditorTheme.TEXT_MUTED.u32,
                    label
                )
                if (selected) list.addRect(
                    x - EditorFonts.px(4f),
                    top + EditorFonts.px(2f),
                    x + EditorFonts.px(16f) + Widgets.textWidth(label),
                    top + height - EditorFonts.px(2f),
                    EditorTheme.SELECTION.u32,
                    EditorFonts.px(3f),
                    0,
                    1.5f
                )
            }
        }
    }

    private fun timelapses(list: ImDrawList, session: EditorSession, spec: TrackSpec) {
        val top = geometry.laneTop(spec.kind)
        val height = geometry.laneHeight(spec.kind)
        val selection = session.selection.timelapseIds
        val muted = session.project.lane(spec.kind).muted
        val color = EditorTheme.WARNING.u32(if (muted) 0.4f else 1f)
        val midY = top + height / 2f
        val h = EditorFonts.px(5f)
        EditorFonts.with(EditorFonts.small) {
            for (mark in cache.timelapses) {
                val selected = mark.id in selection
                val x = geometry.xAt(geometry.shown(mark.nanos, selected))
                if (x < geometry.originX - 12f || x > geometry.right + 12f) continue
                list.addTriangleFilled(x - 2f, midY - h, x + 4f, midY, x - 2f, midY + h, color)
                list.addTriangleFilled(x + 3f, midY - h, x + 9f, midY, x + 3f, midY + h, color)
                val label = String.format("+%.1fs", mark.skipNanos / Nanos.PER_SECOND.toDouble())
                list.addText(x + EditorFonts.px(14f), top + (height - ImGui.getFontSize()) / 2f, EditorTheme.TEXT_MUTED.u32, label)
                if (selected) list.addRect(
                    x - EditorFonts.px(5f), top + EditorFonts.px(2f),
                    x + EditorFonts.px(18f) + Widgets.textWidth(label), top + height - EditorFonts.px(2f),
                    EditorTheme.SELECTION.u32, EditorFonts.px(3f), 0, 1.5f
                )
            }
        }
    }

    private fun events(list: ImDrawList, session: EditorSession, spec: TrackSpec) {
        val top = geometry.laneTop(spec.kind)
        val cy = top + geometry.laneHeight(spec.kind) / 2f
        val events = session.events.events
        if (events.isEmpty()) return
        var index = lowerBound(events, geometry.leftNanos - Nanos.PER_SECOND)
        val end = geometry.rightNanos + Nanos.PER_SECOND
        val r = EditorFonts.px(3f)
        while (index < events.size) {
            val event = events[index]
            if (event.nanos > end) break
            val x = geometry.xAt(event.nanos)
            if (x >= geometry.originX - 4f && x <= geometry.right + 4f) list.addCircleFilled(x, cy, r, Widgets.rgbToU32(event.kind.color, 0.9f), 10)
            index++
        }
    }

    private fun glyph(list: ImDrawList, x: Float, y: Float, r: Float, mode: SegmentMode, fill: Int, outline: Int, thickness: Float) {
        when (mode) {
            SegmentMode.LINEAR -> {
                list.addQuadFilled(x, y - r, x + r, y, x, y + r, x - r, y, fill)
                if (outline != 0) list.addQuad(x, y - r, x + r, y, x, y + r, x - r, y, outline, thickness)
            }

            SegmentMode.CATMULL_ROM -> {
                list.addCircleFilled(x, y, r * 0.92f, fill, 16)
                if (outline != 0) list.addCircle(x, y, r * 0.92f, outline, 16, thickness)
            }

            SegmentMode.BEZIER -> {
                list.addQuadFilled(x, y - r, x + r, y, x, y + r, x - r, y, fill)
                if (outline != 0) {
                    list.addQuad(x, y - r, x + r, y, x, y + r, x - r, y, outline, thickness)
                    list.addCircleFilled(x, y, r * 0.28f, outline, 8)
                }
            }

            SegmentMode.HOLD -> {
                val s = r * 0.8f
                list.addRectFilled(x - s, y - s, x + s, y + s, fill, EditorFonts.px(1.5f))
                if (outline != 0) list.addRect(x - s, y - s, x + s, y + s, outline, EditorFonts.px(1.5f), 0, thickness)
            }
        }
    }

    private fun dashed(list: ImDrawList, x1: Float, y1: Float, x2: Float, y2: Float, color: Int) {
        val dash = EditorFonts.px(4f)
        var x = x1
        while (x < x2) {
            list.addLine(x, y1, minOf(x + dash, x2), y2, color, 1f)
            x += dash * 2f
        }
    }

    fun valueY(lane: ValueLane, top: Float, height: Float, value: Double): Float {
        val normalized = if (lane == ValueLane.SPEED) {
            ((ln(value.coerceIn(0.1, 8.0)) / ln(8.0)) + 1.0) / 2.0
        } else {
            ((value - lane.min) / (lane.max - lane.min)).coerceIn(0.0, 1.0)
        }
        val inset = EditorFonts.px(6f)
        return top + height - inset - (height - inset * 2f) * normalized.toFloat()
    }

    fun itemAt(session: EditorSession, mouseX: Float, mouseY: Float): TimelineItem? {
        val lane = geometry.laneAt(mouseY) ?: return null
        return when (lane.kind) {
            LaneKind.CAMERA -> nearestCamera(mouseX)?.let { TimelineItem.CameraKey(it.timeNanos) }
            LaneKind.VIEW -> holdAt(cache.viewKeys, mouseX)?.let { TimelineItem.ViewKey(it.timeNanos) }
            LaneKind.TEXTURE_PACK -> holdAt(cache.packKeys, mouseX)?.let { TimelineItem.PackKey(it.timeNanos) }
            LaneKind.CLIPS -> clipAt(mouseX)?.let { TimelineItem.ClipItem(it.id) }
            LaneKind.MARKERS -> markerAt(mouseX)?.let { TimelineItem.MarkerItem(it.id) }
            LaneKind.TIMELAPSE -> timelapseAt(mouseX)?.let { TimelineItem.TimelapseItem(it.id) }
            LaneKind.MOMENTS -> gameLanes.momentAt(session, mouseX)?.let { TimelineItem.MomentItem(it.id) }
            else -> lane.valueLane?.let { value -> nearestValue(value, mouseX)?.let { TimelineItem.ValueKeyItem(ValueKey(value, it.timeNanos)) } }
        }
    }

    fun nearestCamera(mouseX: Float): CameraKeyframe? {
        var best: CameraKeyframe? = null
        var bestDistance = KEY_RADIUS + EditorFonts.px(4f)
        for (frame in cache.keyframes) {
            val distance = abs(geometry.xAt(frame.timeNanos) - mouseX)
            if (distance <= bestDistance) {
                best = frame
                bestDistance = distance
            }
        }
        return best
    }

    fun nearestValue(lane: ValueLane, mouseX: Float): Keyframe<Double>? =
        cache.values(lane).minByOrNull { abs(geometry.xAt(it.timeNanos) - mouseX) }
            ?.takeIf { abs(geometry.xAt(it.timeNanos) - mouseX) <= VALUE_RADIUS + EditorFonts.px(4f) }

    private fun <T> holdAt(keys: List<Keyframe<T>>, mouseX: Float): Keyframe<T>? {
        val index = keys.indexOfLast { geometry.xAt(it.timeNanos) - 4f <= mouseX }
        if (index < 0) return null
        val next = keys.getOrNull(index + 1)?.timeNanos ?: geometry.duration
        return if (mouseX <= geometry.xAt(next) + 2f) keys[index] else null
    }

    fun clipAt(mouseX: Float): Clip? =
        cache.clips.lastOrNull { mouseX >= geometry.xAt(it.startNanos) - 2f && mouseX <= geometry.xAt(it.endNanos) + 2f }

    fun clipEdge(clip: Clip, mouseX: Float): Int {
        val left = geometry.xAt(clip.startNanos)
        val right = geometry.xAt(clip.endNanos)
        if (right - left < EDGE_GRAB * 3f) return 0
        return when {
            mouseX - left <= EDGE_GRAB -> -1
            right - mouseX <= EDGE_GRAB -> 1
            else -> 0
        }
    }

    fun markerAt(mouseX: Float): TimelineMarker? {
        val markers = cache.markers
        var best: TimelineMarker? = null
        EditorFonts.with(EditorFonts.small) {
            for ((index, marker) in markers.withIndex()) {
                val x = geometry.xAt(marker.nanos)
                val nextX = markers.getOrNull(index + 1)?.let { geometry.xAt(it.nanos) } ?: Float.MAX_VALUE
                val labelEnd = minOf(x + EditorFonts.px(16f) + Widgets.textWidth(marker.label), nextX - EditorFonts.px(4f))
                if (mouseX >= x - EditorFonts.px(5f) && mouseX <= maxOf(labelEnd, x + EditorFonts.px(12f))) best = marker
            }
        }
        return best
    }

    fun timelapseAt(mouseX: Float): TimelapseMark? =
        cache.timelapses.minByOrNull { abs(geometry.xAt(it.nanos) + 4f - mouseX) }
            ?.takeIf { abs(geometry.xAt(it.nanos) + 4f - mouseX) <= EditorFonts.px(10f) }

    fun eventAt(session: EditorSession, mouseX: Float): TimelineEvent? =
        session.events.events.minByOrNull { abs(geometry.xAt(it.nanos) - mouseX) }
            ?.takeIf { abs(geometry.xAt(it.nanos) - mouseX) <= EditorFonts.px(6f) }

    fun hover(session: EditorSession, lane: TrackSpec, item: TimelineItem?, mouseX: Float, mouseY: Float) {
        val project = session.project
        when (item) {
            is TimelineItem.CameraKey -> project.camera.keyframeAt(item.nanos)?.let { frame ->
                ImGui.setMouseCursor(ImGuiMouseCursor.Hand)
                TimelineTooltip.show {
                    TimelineTooltip.title(Icon.KEYFRAME, EditorTheme.KEYFRAME_SMOOTH, "Keyframe", TimeFormat.clock(frame.timeNanos))
                    TimelineTooltip.chips(listOf(frame.mode.label, frame.easing.label, String.format("FOV %.0f", frame.pose.fov)))
                    val position = frame.pose.position
                    val rotation = frame.pose.rotation
                    TimelineTooltip.grid(
                        listOf(
                            TimelineTooltip.Row("Position", listOf("X" to format(position.x), "Y" to format(position.y), "Z" to format(position.z))),
                            TimelineTooltip.Row("Rotation", listOf("Yaw" to format(rotation.yaw), "Pitch" to format(rotation.pitch), "Roll" to format(rotation.roll))),
                        )
                    )
                    TimelineTooltip.hints(listOf("Drag to move", "Alt+drag to duplicate", "Double-click to jump"))
                }
            }

            is TimelineItem.ValueKeyItem -> project.valueTrack(item.key.lane).at(item.key.nanos)?.let { frame ->
                ImGui.setMouseCursor(ImGuiMouseCursor.Hand)
                TimelineTooltip.show {
                    TimelineTooltip.title(lane.icon, lane.color, item.key.lane.label, TimeFormat.clock(frame.timeNanos))
                    TimelineTooltip.chips(listOf(item.key.lane.format(frame.value), frame.mode.label, frame.easing.label))
                    TimelineTooltip.hints(listOf("Drag to move", "Right-click to change the value"))
                }
            }

            is TimelineItem.ViewKey -> project.views.at(item.nanos)?.let { frame ->
                ImGui.setMouseCursor(ImGuiMouseCursor.Hand)
                TimelineTooltip.show {
                    TimelineTooltip.title(lane.icon, lane.color, "View", "from ${TimeFormat.clock(frame.timeNanos)}")
                    TimelineTooltip.chips(viewChips(frame.value))
                    TimelineTooltip.hints(listOf("Drag to move", "Right-click for actions"))
                }
            }

            is TimelineItem.PackKey -> project.packs.at(item.nanos)?.let { frame ->
                ImGui.setMouseCursor(ImGuiMouseCursor.Hand)
                TimelineTooltip.show {
                    TimelineTooltip.title(lane.icon, lane.color, "Texture pack", "from ${TimeFormat.clock(frame.timeNanos)}")
                    TimelineTooltip.chips(if (frame.value.isDefault) listOf("Default textures") else frame.value.packs.map { it.removeSuffix(".zip") })
                    TimelineTooltip.hints(listOf("Drag to move", "Right-click to choose packs"))
                }
            }

            is TimelineItem.ClipItem -> project.clip(item.id)?.let { clip ->
                val edge = clipEdge(clip, mouseX)
                ImGui.setMouseCursor(if (edge != 0) ImGuiMouseCursor.ResizeEW else ImGuiMouseCursor.Hand)
                TimelineTooltip.show {
                    TimelineTooltip.title(Icon.FILM, lane.color, clip.title, "${TimeFormat.clock(clip.startNanos)} to ${TimeFormat.clock(clip.endNanos)}")
                    TimelineTooltip.chips(listOf(TimeFormat.clock(clip.durationNanos)) + clip.tags.take(3))
                    TimelineTooltip.hints(if (edge != 0) listOf("Drag to trim") else listOf("Drag to move", "Double-click to play"))
                }
            }

            is TimelineItem.MarkerItem -> project.marker(item.id)?.let { marker ->
                ImGui.setMouseCursor(ImGuiMouseCursor.Hand)
                TimelineTooltip.show {
                    TimelineTooltip.title(MARKER_ICONS[marker.kind] ?: Icon.MARKER, EditorTheme.Rgb(marker.color), marker.label, TimeFormat.clock(marker.nanos))
                    if (marker.kind != MarkerKind.NOTE) TimelineTooltip.chips(listOf(marker.kind.label))
                    TimelineTooltip.hints(listOf("Drag to move", "Double-click to jump", "Right-click to rename"))
                }
            }

            is TimelineItem.TimelapseItem -> project.timelapse(item.id)?.let { mark ->
                ImGui.setMouseCursor(ImGuiMouseCursor.Hand)
                TimelineTooltip.show {
                    TimelineTooltip.title(Icon.FAST_FORWARD, EditorTheme.WARNING, "Timelapse", TimeFormat.clock(mark.nanos))
                    TimelineTooltip.chips(listOf(String.format("Skips %.1f s", mark.skipNanos / Nanos.PER_SECOND.toDouble())))
                    TimelineTooltip.hints(listOf("Drag to move", "Right-click to edit"))
                }
            }

            is TimelineItem.MomentItem -> gameLanes.moments(session).firstOrNull { it.id == item.id }?.let { moment ->
                gameLanes.hoverMoment(moment, project.moment(moment.id) != null)
            }

            null -> when (lane.kind) {
                LaneKind.EVENTS -> eventAt(session, mouseX)?.let { event ->
                    ImGui.setMouseCursor(ImGuiMouseCursor.Hand)
                    TimelineTooltip.show {
                        TimelineTooltip.title(Icon.CLOCK, EditorTheme.Rgb(event.kind.color), event.kind.label, TimeFormat.clock(event.nanos))
                        TimelineTooltip.text(event.label)
                        TimelineTooltip.hints(listOf("Click to jump"))
                    }
                }

                LaneKind.PLAYERS -> gameLanes.hoverPlayers(session, mouseX, mouseY - geometry.laneTop(lane.kind), lane.rowHeight)
                LaneKind.WORLD -> gameLanes.hoverWorld(mouseX, mouseY - geometry.laneTop(lane.kind), lane.rowHeight)
                else -> Unit
            }
        }
    }

    private fun format(value: Double): String = String.format("%.1f", value)

    private fun viewChips(view: ViewState): List<String> {
        if (view.mode == CameraMode.FREE) return listOf("Free camera")
        val target =
            if (view.targetEntityId == CameraSettings.TARGET_RECORDER) context.replay?.world?.localPlayer?.name ?: "recorder"
            else entityName(view.targetEntityId)
        return listOf(view.mode.label, target)
    }

    fun viewLabel(view: ViewState): String {
        val target =
            if (view.targetEntityId == CameraSettings.TARGET_RECORDER) context.replay?.world?.localPlayer?.name ?: "recorder"
            else entityName(view.targetEntityId)
        return if (view.mode == CameraMode.FREE) "Free camera" else "${view.mode.label}   $target"
    }

    fun packLabel(state: PackState): String =
        if (state.isDefault) "Default textures" else state.packs.joinToString(", ") { it.removeSuffix(".zip") }

    private fun entityName(id: Int): String {
        val shadow = context.replay?.world ?: return "#$id"
        val entity = shadow.entities[id] ?: return "#$id"
        return entity.uuid?.let { shadow.players.profile(it)?.name } ?: "#$id"
    }

    private fun lowerBound(events: List<TimelineEvent>, nanos: Long): Int {
        var low = 0
        var high = events.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (events[middle].nanos < nanos) low = middle + 1 else high = middle
        }
        return low
    }

    companion object {
        val KEY_RADIUS: Float get() = EditorFonts.px(6f)
        val VALUE_RADIUS: Float get() = EditorFonts.px(4.5f)
        val EDGE_GRAB: Float get() = EditorFonts.px(7f)
        val EASE_MIN_WIDTH: Float get() = EditorFonts.px(28f)
        const val CURVE_STEP = 3f
        val MARKER_ICONS = mapOf(
            MarkerKind.MOMENT to Icon.BOOKMARK,
            MarkerKind.SHOT to Icon.FILM,
            MarkerKind.PLAYER to Icon.PERSON,
            MarkerKind.EVENT to Icon.TARGET,
            MarkerKind.CAMERA to Icon.CAMERA,
        )
    }
}
