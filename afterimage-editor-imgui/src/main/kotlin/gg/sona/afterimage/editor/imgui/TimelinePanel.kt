package gg.sona.afterimage.editor.imgui

import gg.sona.afterimage.core.time.Nanos
import gg.sona.afterimage.editor.EditorSession
import gg.sona.afterimage.editor.LaneKind
import gg.sona.afterimage.editor.Selection
import gg.sona.afterimage.editor.commands.SetInOutPoints
import gg.sona.afterimage.editor.commands.SetLaneState
import imgui.ImDrawList
import imgui.ImGui
import imgui.flag.ImGuiHoveredFlags
import imgui.flag.ImGuiKey
import imgui.flag.ImGuiMouseButton
import imgui.flag.ImGuiMouseCursor
import imgui.flag.ImGuiWindowFlags
import java.util.*
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow

class TimelinePanel(private val context: EditorContext) :
    AbstractPanel("Timeline", DockArea.BOTTOM, Icon.CLOCK, flags = ImGuiWindowFlags.NoScrollbar or ImGuiWindowFlags.NoScrollWithMouse) {

    private enum class DragKind { NONE, SCRUB, IN_POINT, OUT_POINT, MOVE, CLIP_START, CLIP_END, PAN, BOX, NAV_THUMB, NAV_LEFT, NAV_RIGHT, SCROLLBAR, REORDER }

    private val geometry = TimelineGeometry()
    private val cache = TimelineCache()
    private val actions = TimelineActions(context)
    private val gameLanes = GameLanes(context, geometry)
    private val lanes = TimelineLanes(context, geometry, cache, gameLanes)
    private val menus = TimelineMenus(context, actions, cache, lanes, gameLanes)

    private var drag = DragKind.NONE
    private var dragItem: TimelineItem? = null
    private var dragAdditiveToggle = false
    private var dragMoved = false
    private var dragStartX = 0f
    private var dragStartY = 0f
    private var dragOriginNanos = 0L
    private var dragCurrentNanos = 0L
    private var dragAnchorNanos = 0L
    private var dragClip: UUID? = null
    private var navOriginOffset = 0L
    private var navOriginVisible = 0L
    private var scrollOrigin = 0f
    private var scrubTarget = -1L
    private var lastScrubSeekNanos = 0L
    private var reorderKind: LaneKind? = null
    private var contextItem: TimelineItem? = null
    private var contextNanos = 0L
    private var contextLane: TrackSpec? = null
    private var contextTrack: TrackSpec? = null
    private var trackMenuRequested = false
    private var activeHeader: LaneKind? = null

    private var originY = 0f
    private var canvasHeight = 0f
    private var duration = 1L

    override fun content(frame: FrameContext) {
        val session = context.session
        val replay = session?.replay
        if (session == null || replay == null) {
            Widgets.emptyState("No replay open", "Open a recording from the library to edit its timeline", Icon.FILM)
            return
        }
        val view = context.timeline
        duration = maxOf(1L, replay.durationNanos)
        if (cache.refresh(session)) {
            geometry.scrollY = 0f
            drag = DragKind.NONE
        }
        gameLanes.prepare(session)
        toolbar(session, view)
        layout(session, view)
        val list = ImGui.getWindowDrawList()
        list.addRectFilled(geometry.headerX, originY, geometry.right, originY + canvasHeight, EditorTheme.PANEL_SUNKEN.u32)

        ImGui.setCursorScreenPos(geometry.originX, originY)
        ImGui.invisibleButton("timeline-canvas", geometry.width, canvasHeight)
        val hovered = ImGui.isItemHovered()
        val mouseX = ImGui.getMousePosX()
        val mouseY = ImGui.getMousePosY()
        val overTracks = hovered && mouseY >= geometry.tracksTop && mouseY < geometry.tracksBottom
        lanes.hovered = if (overTracks && drag == DragKind.NONE) lanes.itemAt(session, mouseX, mouseY) else null

        list.pushClipRect(geometry.originX, geometry.tracksTop, geometry.right, geometry.tracksBottom, true)
        laneBackgrounds(list)
        workArea(list, session)
        grid(list, view)
        segments(list, session)
        lanes.drawGuides(list, session)
        lanes.draw(list, session, frame.nowNanos)
        if (drag == DragKind.BOX && dragMoved) box(list)
        list.popClipRect()

        ruler(list, session, view)
        list.pushClipRect(geometry.originX, geometry.rulerTop, geometry.right, geometry.tracksBottom, true)
        playhead(list, session)
        if (hovered && drag == DragKind.NONE && mouseY < geometry.tracksBottom) skimmer(list, mouseX)
        list.popClipRect()
        navigator(list, view)
        scrollbar(list)
        headers(session, view)

        if (!hovered && drag == DragKind.NONE && ImGui.isWindowHovered() && ImGui.getIO().mouseWheel != 0f && mouseX >= geometry.headerX && mouseX < geometry.originX && mouseY >= geometry.tracksTop && mouseY < geometry.tracksBottom)
            scrollBy(-ImGui.getIO().mouseWheel * EditorFonts.px(40f))
        if (hovered || drag != DragKind.NONE) input(session, view, hovered, mouseX, mouseY, frame.nowNanos)
        if (hovered && drag == DragKind.NONE) hoverFeedback(session, mouseX, mouseY)
        if (hovered && ImGui.isMouseClicked(ImGuiMouseButton.Right)) {
            prepareContext(session, mouseX, mouseY)
            ImGui.openPopup("timeline-context")
        }
        if (Widgets.beginPopup("timeline-context")) {
            val item = contextItem
            if (item != null) menus.item(session, item) else menus.empty(session, contextNanos, contextLane, geometry.lanes)
            Widgets.endPopup()
        }
        if (Widgets.beginPopup("track-menu")) {
            contextTrack?.let { menus.track(session, view, it) }
            Widgets.endPopup()
        }
        keyboard(session, hovered)
        ImGui.setCursorScreenPos(geometry.headerX, originY + canvasHeight)
        ImGui.dummy(1f, 1f)
    }

    private fun layout(session: EditorSession, view: TimelineView) {
        val order = context.ui.laneOrder
        val visible = TrackSpec.ALL.filter { visible(it, view) }.sortedBy { order.indexOf(it.kind) }
        geometry.headerX = ImGui.getCursorScreenPosX()
        geometry.originX = geometry.headerX + HEADER_WIDTH
        geometry.width = maxOf(1f, ImGui.getContentRegionAvailX() - HEADER_WIDTH)
        originY = ImGui.getCursorScreenPosY()
        canvasHeight = maxOf(RULER_HEIGHT + EditorFonts.px(40f) + NAV_GAP + NAV_HEIGHT, ImGui.getContentRegionAvailY() - 1f)
        geometry.duration = duration
        geometry.rulerTop = originY
        geometry.tracksTop = originY + RULER_HEIGHT
        geometry.tracksBottom = originY + canvasHeight - NAV_HEIGHT - NAV_GAP
        geometry.visibleNanos = maxOf(1L, (duration / view.zoom).toLong())
        if (view.followPlayhead && session.replay?.playing == true && drag == DragKind.NONE) follow(session.playheadNanos, view)
        view.offsetNanos = view.offsetNanos.coerceIn(0L, maxOf(0L, duration - geometry.visibleNanos))
        geometry.offsetNanos = view.offsetNanos
        geometry.layout(visible) { heightOf(it) }
        val viewport = geometry.tracksBottom - geometry.tracksTop
        geometry.scrollY = geometry.scrollY.coerceIn(0f, maxOf(0f, geometry.contentHeight - viewport))
        geometry.layout(visible) { heightOf(it) }
        geometry.dragActive = drag == DragKind.MOVE && dragMoved
        geometry.dragDeltaNanos = if (geometry.dragActive) dragCurrentNanos - dragOriginNanos else 0L
    }

    private fun heightOf(spec: TrackSpec): Float = when (spec.kind) {
        LaneKind.PLAYERS -> spec.rowHeight * maxOf(1, gameLanes.playerRowCount())
        LaneKind.WORLD -> spec.rowHeight * maxOf(1, gameLanes.worldRows().size)
        else -> spec.rowHeight
    }

    private fun visible(spec: TrackSpec, view: TimelineView): Boolean = when (spec.kind) {
        LaneKind.CAMERA -> true
        LaneKind.EVENTS, LaneKind.PLAYERS, LaneKind.WORLD, LaneKind.MOMENTS -> spec.kind in view.shownLanes
        else -> cache.hasContent(spec.kind) || spec.kind in view.shownLanes
    }

    private fun toolbar(session: EditorSession, view: TimelineView) {
        val replay = session.replay ?: return
        EditorTheme.pushToolbarStyle()
        try {
            val rowY = ImGui.getCursorScreenPosY()
            val right = ImGui.getCursorScreenPosX() + ImGui.getContentRegionAvailX()
            if (Widgets.iconButton("tl-key", Icon.KEYFRAME_ADD, TOOL_SIZE, "Add camera keyframe at playhead  Ctrl+K")) actions.addCameraKeyframe(session, replay.positionNanos)
            ImGui.sameLine()
            if (Widgets.iconButton("tl-marker", Icon.MARKER, TOOL_SIZE, "Add marker at playhead  M")) actions.addMarker(session, replay.positionNanos)
            Widgets.verticalSeparator(TOOL_SIZE)
            if (Widgets.iconButton("tl-in", Icon.MARK_IN, TOOL_SIZE, "Set in point at playhead  I")) actions.setInPoint(session, replay.positionNanos)
            ImGui.sameLine()
            if (Widgets.iconButton("tl-out", Icon.MARK_OUT, TOOL_SIZE, "Set out point at playhead  O")) actions.setOutPoint(session, replay.positionNanos)
            ImGui.sameLine()
            if (Widgets.iconButton("tl-clip", Icon.FILM, TOOL_SIZE, "Save the in to out range as a clip", enabled = actions.trimmed(session))) actions.clipFromInOut(session)
            ImGui.sameLine()
            Widgets.iconToggle("tl-loop", Icon.LOOP, context.loopPlayback, TOOL_SIZE, "Loop between the in and out points")
                ?.let { context.loopPlayback = it }
            val rightWidth = Widgets.lastWidth("tl-right")
            ImGui.sameLine()
            ImGui.setCursorScreenPos(maxOf(ImGui.getCursorScreenPosX(), right - rightWidth), rowY)
            Widgets.measured("tl-right") {
                Widgets.iconToggle("tl-snap", Icon.MAGNET, view.snapToTicks, TOOL_SIZE, "Snap to frames, keyframes and markers  hold Shift to bypass")
                    ?.let { view.snapToTicks = it }
                ImGui.sameLine()
                Widgets.iconToggle("tl-follow", Icon.FOLLOW, view.followPlayhead, TOOL_SIZE, "Keep the playhead in view while playing  Shift+F")
                    ?.let { view.followPlayhead = it }
                Widgets.verticalSeparator(TOOL_SIZE)
                zoomSlider(view, session)
                ImGui.sameLine()
                if (Widgets.iconButton("tl-fit", Icon.FIT, TOOL_SIZE, "Fit the whole replay  Shift+Z")) {
                    view.zoom = 1.0
                    view.offsetNanos = 0L
                }
            }
        } finally {
            EditorTheme.popToolbarStyle()
        }
        ImGui.dummy(0f, EditorFonts.px(2f))
    }

    private fun zoomSlider(view: TimelineView, session: EditorSession) {
        val width = EditorFonts.px(96f)
        val height = TOOL_SIZE
        val x = ImGui.getCursorScreenPosX()
        val y = ImGui.getCursorScreenPosY()
        ImGui.invisibleButton("tl-zoom", width, height)
        val hovered = ImGui.isItemHovered()
        val active = ImGui.isItemActive()
        val maxZoom = maxOf(1.0, duration.toDouble() / MIN_VISIBLE)
        val t = (ln(view.zoom) / ln(maxZoom)).toFloat().coerceIn(0f, 1f)
        val list = ImGui.getWindowDrawList()
        val pad = EditorFonts.px(7f)
        val cy = y + height / 2f
        val track = EditorFonts.px(3f)
        list.addRectFilled(x + pad, cy - track / 2f, x + width - pad, cy + track / 2f, EditorTheme.CONTROL_HOVER.u32, track / 2f)
        val kx = x + pad + (width - pad * 2f) * t
        list.addRectFilled(x + pad, cy - track / 2f, kx, cy + track / 2f, EditorTheme.ACCENT.u32, track / 2f)
        list.addCircleFilled(kx, cy, EditorFonts.px(if (active) 6f else 5f), EditorTheme.TEXT.u32, 16)
        if (active) {
            val next = ((ImGui.getMousePosX() - x - pad) / (width - pad * 2f)).coerceIn(0f, 1f)
            val zoom = maxZoom.pow(next.toDouble()).coerceIn(1.0, maxZoom)
            if (zoom != view.zoom) zoomAround(view, zoom / view.zoom, session.playheadNanos)
        }
        if (hovered || active) Widgets.hint(String.format("Zoom %.1fx   wheel zooms at the cursor, Shift+wheel scrolls", view.zoom))
    }

    private fun laneBackgrounds(list: ImDrawList) {
        val mouseY = ImGui.getMousePosY()
        val mouseX = ImGui.getMousePosX()
        val windowHovered = ImGui.isWindowHovered()
        for ((index, lane) in geometry.lanes.withIndex()) {
            if (!geometry.laneVisible(lane.kind)) continue
            val top = geometry.laneTop(lane.kind)
            val bottom = top + geometry.laneHeight(lane.kind)
            list.addRectFilled(geometry.originX, top, geometry.right, bottom, (if (index % 2 == 0) EditorTheme.LANE_A else EditorTheme.LANE_B).u32)
            if (drag == DragKind.NONE && windowHovered && mouseX >= geometry.headerX && mouseY >= top && mouseY < bottom)
                list.addRectFilled(geometry.originX, top, geometry.right, bottom, EditorTheme.TEXT.u32(0.025f))
            list.addLine(geometry.originX, bottom, geometry.right, bottom, EditorTheme.LANE_LINE.u32, 1f)
        }
    }

    private fun workArea(list: ImDrawList, session: EditorSession) {
        val inNanos = if (drag == DragKind.IN_POINT) dragCurrentNanos else actions.inPoint(session)
        val outNanos = if (drag == DragKind.OUT_POINT) dragCurrentNanos else actions.outPoint(session)
        val left = geometry.xAt(inNanos)
        val right = geometry.xAt(outNanos)
        val top = geometry.tracksTop
        val bottom = geometry.tracksBottom
        if (left > geometry.originX) list.addRectFilled(geometry.originX, top, minOf(left, geometry.right), bottom, EditorTheme.APP_BG.u32(0.4f))
        if (right < geometry.right) list.addRectFilled(maxOf(right, geometry.originX), top, geometry.right, bottom, EditorTheme.APP_BG.u32(0.4f))
        if (inNanos > 0L || outNanos < duration) {
            val color = EditorTheme.SELECTION.u32(0.45f)
            if (left >= geometry.originX && left <= geometry.right) list.addLine(left, top, left, bottom, color, 1f)
            if (right >= geometry.originX && right <= geometry.right) list.addLine(right, top, right, bottom, color, 1f)
        }
    }

    private fun grid(list: ImDrawList, view: TimelineView) {
        val step = rulerStep(view)
        var tick = (view.offsetNanos / step) * step
        val end = view.offsetNanos + geometry.visibleNanos
        while (tick <= end) {
            val x = geometry.xAt(tick)
            if (x >= geometry.originX && x <= geometry.right) list.addLine(x, geometry.tracksTop, x, geometry.tracksBottom, EditorTheme.LANE_LINE.u32(0.16f), 1f)
            tick += step
        }
    }

    private fun segments(list: ImDrawList, session: EditorSession) {
        val project = session.project
        if (!project.isSequence) return
        val spans = project.segmentSpans()
        for ((index, span) in spans.withIndex()) {
            val x1 = geometry.xAt(span.first).coerceIn(geometry.originX, geometry.right)
            val x2 = geometry.xAt(span.second).coerceIn(geometry.originX, geometry.right)
            if (x2 <= x1) continue
            val color = SEGMENT_COLORS[index % SEGMENT_COLORS.size]
            list.addRectFilled(x1, geometry.tracksTop, x2, geometry.tracksBottom, color.u32(0.04f))
            list.addLine(x1, geometry.tracksTop, x1, geometry.tracksBottom, color.u32(0.45f), 1f)
        }
    }

    private fun ruler(list: ImDrawList, session: EditorSession, view: TimelineView) {
        val top = geometry.rulerTop
        val bottom = geometry.tracksTop
        list.addRectFilled(geometry.originX, top, geometry.right, bottom, EditorTheme.RULER_BG.u32)
        list.pushClipRect(geometry.originX, top, geometry.right, bottom, true)
        val project = session.project
        if (project.isSequence) {
            for ((index, span) in project.segmentSpans().withIndex()) {
                val x1 = geometry.xAt(span.first).coerceIn(geometry.originX, geometry.right)
                val x2 = geometry.xAt(span.second).coerceIn(geometry.originX, geometry.right)
                if (x2 <= x1) continue
                val color = SEGMENT_COLORS[index % SEGMENT_COLORS.size]
                list.addRectFilled(x1, top, x2, top + EditorFonts.px(2f), color.u32(0.8f))
                val label = "${index + 1}  " + project.segments[index].gameplay.fileName.toString().substringBeforeLast('.')
                EditorFonts.with(EditorFonts.small) {
                    val labelWidth = Widgets.textWidth(label)
                    if (labelWidth + EditorFonts.px(12f) < x2 - x1) list.addText(x2 - labelWidth - EditorFonts.px(6f), top + EditorFonts.px(3f), color.u32(0.75f), label)
                }
            }
        }
        val step = rulerStep(view)
        val minor = minorStep(step, view)
        val labelBand = top + EditorFonts.px(16f)
        var tick = (view.offsetNanos / minor) * minor
        val end = view.offsetNanos + geometry.visibleNanos
        EditorFonts.with(EditorFonts.small) {
            while (tick <= end) {
                val x = geometry.xAt(tick)
                if (x >= geometry.originX && x <= geometry.right) {
                    val major = tick % step == 0L
                    val height = if (major) EditorFonts.px(7f) else EditorFonts.px(3f)
                    list.addLine(x, labelBand - height, x, labelBand, if (major) EditorTheme.TEXT_MUTED.u32(0.8f) else EditorTheme.TEXT_DIM.u32(0.5f), 1f)
                    if (major) list.addText(x + EditorFonts.px(4f), top + EditorFonts.px(2f), EditorTheme.TEXT_MUTED.u32, rulerLabel(tick, step, view))
                }
                tick += minor
            }
        }
        workAreaBar(list, session)
        list.addLine(geometry.originX, bottom - 1f, geometry.right, bottom - 1f, EditorTheme.BORDER.u32, 1f)
        list.popClipRect()
    }

    private fun workAreaBar(list: ImDrawList, session: EditorSession) {
        val inNanos = if (drag == DragKind.IN_POINT) dragCurrentNanos else actions.inPoint(session)
        val outNanos = if (drag == DragKind.OUT_POINT) dragCurrentNanos else actions.outPoint(session)
        val trimmed = inNanos > 0L || outNanos < duration
        val left = geometry.xAt(inNanos)
        val right = geometry.xAt(outNanos)
        val cy = geometry.tracksTop - EditorFonts.px(7f)
        val half = EditorFonts.px(2f)
        val color = if (trimmed) EditorTheme.SELECTION else EditorTheme.IN_OUT
        val x1 = left.coerceIn(geometry.originX, geometry.right)
        val x2 = right.coerceIn(geometry.originX, geometry.right)
        if (x2 > x1) list.addRectFilled(x1, cy - half, x2, cy + half, color.u32(if (trimmed) 0.85f else 0.3f), half)
        val handle = if (drag == DragKind.NONE) handleAt(session, ImGui.getMousePosX(), ImGui.getMousePosY()) else null
        handle(list, left, cy, color, trimmed || handle == DragKind.IN_POINT || drag == DragKind.IN_POINT)
        handle(list, right, cy, color, trimmed || handle == DragKind.OUT_POINT || drag == DragKind.OUT_POINT)
    }

    private fun handle(list: ImDrawList, x: Float, cy: Float, color: EditorTheme.Rgb, bright: Boolean) {
        if (x < geometry.originX - HANDLE_WIDTH || x > geometry.right + HANDLE_WIDTH) return
        val half = HANDLE_WIDTH / 2f
        val h = HANDLE_HEIGHT / 2f
        list.addRectFilled(x - half, cy - h, x + half, cy + h, color.u32(if (bright) 1f else 0.55f), EditorFonts.px(2f))
        list.addLine(x, cy - h * 0.45f, x, cy + h * 0.45f, EditorTheme.PANEL_SUNKEN.u32(0.7f), 1f)
    }

    private fun rulerLabel(nanos: Long, step: Long, view: TimelineView): String =
        if (step < Nanos.PER_SECOND) TimeFormat.timecode(nanos, view.renderFps) else TimeFormat.clock(nanos).substringBefore('.')

    private fun rulerStep(view: TimelineView): Long {
        val target = (LABEL_SPACING / geometry.width * geometry.visibleNanos).toLong()
        val frame = view.frameNanos()
        for (frames in FRAME_STEPS) {
            val step = frames * frame
            if (step >= Nanos.PER_SECOND) break
            if (step >= target) return step
        }
        return SECOND_STEPS.firstOrNull { it >= target } ?: SECOND_STEPS.last()
    }

    private fun minorStep(step: Long, view: TimelineView): Long {
        val frame = view.frameNanos()
        if (step < Nanos.PER_SECOND) {
            val framePixels = frame / geometry.nanosPerPixel
            return if (framePixels >= 6.0 && step > frame) frame else step
        }
        val divisions = when (step) {
            Nanos.ofSeconds(15), Nanos.ofSeconds(30), Nanos.ofSeconds(1800) -> 3L
            Nanos.ofSeconds(60), Nanos.ofSeconds(120) -> 4L
            else -> 5L
        }
        return step / divisions
    }

    private fun playhead(list: ImDrawList, session: EditorSession) {
        val replay = session.replay ?: return
        val nanos = if (drag == DragKind.SCRUB && scrubTarget >= 0L) scrubTarget else replay.positionNanos
        val x = geometry.xAt(nanos)
        if (x < geometry.originX - 8f || x > geometry.right + 8f) return
        list.addLine(x, geometry.rulerTop + EditorFonts.px(2f), x, geometry.tracksBottom, EditorTheme.PLAYHEAD.u32, 1.5f)
        val half = EditorFonts.px(5f)
        val headTop = geometry.rulerTop + EditorFonts.px(1f)
        val headBottom = headTop + EditorFonts.px(8f)
        list.addRectFilled(x - half, headTop, x + half, headBottom, EditorTheme.PLAYHEAD.u32, EditorFonts.px(2f))
        list.addTriangleFilled(x - half, headBottom - 1f, x + half, headBottom - 1f, x, headBottom + half, EditorTheme.PLAYHEAD.u32)
        if (drag != DragKind.SCRUB) return
        val label = TimeFormat.timecode(nanos, context.timeline.renderFps)
        EditorFonts.with(EditorFonts.smallMedium) {
            val labelWidth = Widgets.textWidth(label)
            val pad = EditorFonts.px(5f)
            val labelX = if (x + EditorFonts.px(10f) + labelWidth + pad * 2f > geometry.right) x - EditorFonts.px(10f) - labelWidth - pad * 2f else x + EditorFonts.px(9f)
            val y = geometry.rulerTop + EditorFonts.px(2f)
            list.addRectFilled(labelX, y, labelX + labelWidth + pad * 2f, y + ImGui.getFontSize() + EditorFonts.px(4f), EditorTheme.PLAYHEAD.u32(0.92f), EditorFonts.px(3f))
            list.addText(labelX + pad, y + EditorFonts.px(2f), EditorTheme.PRIMARY_TEXT.u32, label)
        }
    }

    private fun skimmer(list: ImDrawList, mouseX: Float) {
        if (mouseX < geometry.originX || mouseX > geometry.right) return
        list.addLine(mouseX, geometry.tracksTop, mouseX, geometry.tracksBottom, EditorTheme.SKIMMER.u32(0.45f), 1f)
        val label = TimeFormat.clock(geometry.nanosAt(mouseX).coerceIn(0L, duration))
        EditorFonts.with(EditorFonts.small) {
            val labelWidth = Widgets.textWidth(label)
            val pad = EditorFonts.px(3f)
            val labelX = if (mouseX + EditorFonts.px(8f) + labelWidth + pad * 2f > geometry.right) mouseX - EditorFonts.px(8f) - labelWidth - pad * 2f else mouseX + EditorFonts.px(6f)
            val y = geometry.rulerTop + EditorFonts.px(2f)
            list.addRectFilled(labelX, y, labelX + labelWidth + pad * 2f, y + ImGui.getFontSize() + EditorFonts.px(2f), EditorTheme.APP_BG.u32(0.9f), EditorFonts.px(3f))
            list.addText(labelX + pad, y + 1f, EditorTheme.TEXT_MUTED.u32, label)
        }
    }

    private fun box(list: ImDrawList) {
        val x1 = minOf(dragStartX, ImGui.getMousePosX())
        val x2 = maxOf(dragStartX, ImGui.getMousePosX())
        val y1 = minOf(dragStartY, ImGui.getMousePosY())
        val y2 = maxOf(dragStartY, ImGui.getMousePosY())
        list.addRectFilled(x1, y1, x2, y2, EditorTheme.TEXT.u32(0.07f))
        list.addRect(x1, y1, x2, y2, EditorTheme.TEXT.u32(0.55f), 0f, 0, 1f)
    }

    private fun navTop(): Float = originY + canvasHeight - NAV_HEIGHT

    private fun navigator(list: ImDrawList, view: TimelineView) {
        val top = navTop()
        val bottom = top + NAV_HEIGHT
        list.addRectFilled(geometry.originX, top, geometry.right, bottom, EditorTheme.PANEL.u32, NAV_HEIGHT / 2f)
        for (time in cache.keyTimes) {
            val x = navX(time)
            list.addRectFilled(x - 1f, top + 2f, x + 1f, bottom - 2f, EditorTheme.KEYFRAME_SMOOTH.u32(0.3f))
        }
        for (marker in cache.markers) {
            val x = navX(marker.nanos)
            list.addRectFilled(x - 1f, top + 2f, x + 1f, bottom - 2f, Widgets.rgbToU32(marker.color, 0.5f))
        }
        val left = navX(view.offsetNanos)
        val right = maxOf(navX(view.offsetNanos + geometry.visibleNanos), left + NAV_HEIGHT)
        val hovered = drag == DragKind.NONE && ImGui.isWindowHovered() && ImGui.isMouseHoveringRect(geometry.originX, top, geometry.right, bottom)
        val dragging = drag == DragKind.NAV_THUMB || drag == DragKind.NAV_LEFT || drag == DragKind.NAV_RIGHT
        list.addRectFilled(left, top + 1f, right, bottom - 1f, EditorTheme.TEXT.u32(if (hovered || dragging) 0.3f else 0.18f), NAV_HEIGHT / 2f)
        val playX = navX(context.replay?.positionNanos ?: 0L)
        list.addRectFilled(playX - 1f, top + 1f, playX + 1f, bottom - 1f, EditorTheme.PLAYHEAD.u32, 1f)
    }

    private fun navX(nanos: Long): Float = geometry.originX + (nanos.toDouble() / duration * geometry.width).toFloat()

    private fun navNanos(x: Float): Long = ((x - geometry.originX) / geometry.width * duration).toLong()

    private fun scrollbar(list: ImDrawList) {
        val viewport = geometry.tracksBottom - geometry.tracksTop
        if (geometry.contentHeight <= viewport) return
        val x2 = geometry.right - EditorFonts.px(2f)
        val x1 = x2 - SCROLLBAR_WIDTH
        val fraction = viewport / geometry.contentHeight
        val thumbHeight = maxOf(EditorFonts.px(18f), viewport * fraction)
        val thumbTop = geometry.tracksTop + (viewport - thumbHeight) * (geometry.scrollY / (geometry.contentHeight - viewport))
        val hovered = drag == DragKind.NONE && ImGui.isWindowHovered() && scrollbarHit(ImGui.getMousePosX(), ImGui.getMousePosY())
        list.addRectFilled(x1, thumbTop, x2, thumbTop + thumbHeight, EditorTheme.TEXT.u32(if (hovered || drag == DragKind.SCROLLBAR) 0.3f else 0.14f), SCROLLBAR_WIDTH / 2f)
    }

    private fun scrollbarHit(mouseX: Float, mouseY: Float): Boolean {
        val viewport = geometry.tracksBottom - geometry.tracksTop
        if (geometry.contentHeight <= viewport) return false
        val x2 = geometry.right - EditorFonts.px(2f)
        return mouseX >= x2 - SCROLLBAR_WIDTH - EditorFonts.px(3f) && mouseX <= x2 + EditorFonts.px(2f) && mouseY >= geometry.tracksTop && mouseY < geometry.tracksBottom
    }

    private fun headers(session: EditorSession, view: TimelineView) {
        val list = ImGui.getWindowDrawList()
        val headerX = geometry.headerX
        val originX = geometry.originX
        list.addRectFilled(headerX, geometry.rulerTop, originX, geometry.tracksBottom, EditorTheme.LANE_HEADER.u32)
        addTrackButton(view)
        val mouseX = ImGui.getMousePosX()
        val mouseY = ImGui.getMousePosY()
        val windowHovered = ImGui.isWindowHovered(ImGuiHoveredFlags.AllowWhenBlockedByActiveItem)
        val held = activeHeader
        activeHeader = null
        val menuOpen = ImGui.isPopupOpen("track-menu")
        ImGui.pushClipRect(headerX, geometry.tracksTop, originX, geometry.tracksBottom, true)
        try {
            for (lane in geometry.lanes) {
                if (!geometry.laneVisible(lane.kind)) continue
                val top = geometry.laneTop(lane.kind)
                val height = geometry.laneHeight(lane.kind)
                val rowHovered = drag == DragKind.NONE && windowHovered && mouseX >= headerX && mouseX < originX && mouseY >= maxOf(top, geometry.tracksTop) && mouseY < minOf(top + height, geometry.tracksBottom)
                val state = session.project.lane(lane.kind)
                val selected = context.inspect == InspectTarget.Lane(lane.kind) && session.selection.isEmpty
                if (selected) list.addRectFilled(headerX, top, originX, top + height, EditorTheme.SELECTION_FILL.u32)
                else if (rowHovered || reorderKind == lane.kind) list.addRectFilled(headerX, top, originX, top + height, EditorTheme.TEXT.u32(0.04f))
                list.addRectFilled(headerX + EditorFonts.px(4f), top + EditorFonts.px(5f), headerX + EditorFonts.px(6f), top + height - EditorFonts.px(5f), if (state.muted) EditorTheme.TEXT_DIM.u32(0.4f) else lane.color.u32, 1f)
                list.addLine(headerX, top + height, originX, top + height, EditorTheme.LANE_LINE.u32, 1f)
                val iconSize = EditorFonts.px(13f)
                Icons.draw(list, lane.icon, headerX + EditorFonts.px(12f), top + (lane.rowHeight - iconSize) / 2f, iconSize, if (state.muted) EditorTheme.TEXT_DIM.u32 else lane.color.u32)
                headerRow(list, session, lane, top, height, rowHovered || menuOpen && contextTrack == lane || held == lane.kind)
            }
            reorderKind?.let { kind ->
                if (geometry.lanes.any { it.kind == kind }) list.addRect(headerX + 1f, geometry.laneTop(kind) + 1f, originX - 1f, geometry.laneTop(kind) + geometry.laneHeight(kind) - 1f, EditorTheme.SELECTION.u32, EditorFonts.px(3f), 0, 1.5f)
            }
        } finally {
            ImGui.popClipRect()
        }
        if (trackMenuRequested) {
            trackMenuRequested = false
            ImGui.openPopup("track-menu")
        }
        list.addLine(originX - 1f, geometry.rulerTop, originX - 1f, geometry.tracksBottom, EditorTheme.BORDER.u32, 1f)
        list.addLine(headerX, geometry.tracksTop - 1f, originX, geometry.tracksTop - 1f, EditorTheme.BORDER.u32, 1f)
    }

    private fun headerRow(list: ImDrawList, session: EditorSession, lane: TrackSpec, top: Float, height: Float, rowHovered: Boolean) {
        val headerX = geometry.headerX
        val originX = geometry.originX
        val state = session.project.lane(lane.kind)
        val size = EditorFonts.px(18f)
        val buttonY = top + (lane.rowHeight - size) / 2f
        ImGui.pushID("track-${lane.kind.name}")
        try {
            ImGui.setCursorScreenPos(headerX, top)
            ImGui.setNextItemAllowOverlap()
            ImGui.invisibleButton("hit", HEADER_WIDTH, height)
            val hit = ImGui.isItemHovered()
            if (ImGui.isItemClicked(ImGuiMouseButton.Left)) {
                if (lane.kind == LaneKind.PLAYERS) gameLanes.togglePlayer(ImGui.getMousePosY() - top, lane.rowHeight)
                else if (lane.kind in INSPECTABLE) {
                    session.selection = Selection.NONE
                    context.selectedEntityId = null
                    context.inspect = InspectTarget.Lane(lane.kind)
                }
            }
            if (ImGui.isItemClicked(ImGuiMouseButton.Right)) {
                contextTrack = lane
                trackMenuRequested = true
            }
            if (ImGui.isItemActive()) {
                if (drag == DragKind.NONE && abs(ImGui.getMouseDragDeltaY(ImGuiMouseButton.Left, 0f)) > EditorFonts.px(6f)) {
                    drag = DragKind.REORDER
                    reorderKind = lane.kind
                }
                if (drag == DragKind.REORDER && reorderKind == lane.kind) geometry.laneAt(ImGui.getMousePosY())?.let { target ->
                    if (target.kind != lane.kind) reorder(lane.kind, target.kind)
                }
            } else if (drag == DragKind.REORDER && reorderKind == lane.kind) {
                drag = DragKind.NONE
                reorderKind = null
            }
            if (hit && drag == DragKind.NONE && ImGui.getMousePosX() < originX - EditorFonts.px(70f)) Widgets.hint(
                when (lane.kind) {
                    LaneKind.PLAYERS -> "Click a player to expand health, speed and actions   Drag to reorder tracks"
                    in INSPECTABLE -> "${lane.hint}\nClick to inspect the track   Drag to reorder"
                    else -> "${lane.hint}\nDrag to reorder tracks"
                }
            )
            when (lane.kind) {
                LaneKind.PLAYERS -> gameLanes.drawPlayerHeaders(list, top, lane.rowHeight, headerX, originX)
                LaneKind.WORLD -> gameLanes.drawWorldHeaders(list, top, lane.rowHeight, headerX)
                else -> Unit
            }
            val reveal = rowHovered
            var x = originX - EditorFonts.px(6f) - size
            var used = EditorFonts.px(6f)
            if (reveal && lane.kind != LaneKind.PLAYERS && lane.kind != LaneKind.WORLD && lane.kind != LaneKind.EVENTS) {
                ImGui.setCursorScreenPos(x, buttonY)
                val tooltip = when (lane.kind) {
                    LaneKind.CAMERA -> "Add camera keyframe at playhead  Ctrl+K"
                    LaneKind.VIEW -> "Add view keyframe: keeps the current camera mode and target from here on"
                    LaneKind.TEXTURE_PACK -> "Add texture pack keyframe: the packs chosen here stay applied until the next keyframe"
                    LaneKind.TIMELAPSE -> "Add timelapse skip at playhead"
                    LaneKind.MARKERS -> "Add marker at playhead  M"
                    LaneKind.MOMENTS -> "Add a moment at the playhead"
                    LaneKind.CLIPS -> "Save the in to out range as a clip"
                    else -> "Add ${lane.label.lowercase()} keyframe with the current value"
                }
                val enabled = lane.kind != LaneKind.CLIPS || actions.trimmed(session)
                if (Widgets.iconButton("add", Icon.PLUS, size, tooltip, enabled = enabled, iconScale = 0.55f)) addAtPlayhead(session, lane)
                if (ImGui.isItemActive()) activeHeader = lane.kind
                x -= size + EditorFonts.px(2f)
                used += size + EditorFonts.px(2f)
            }
            if (lane.kind == LaneKind.CAMERA && (reveal || state.locked)) {
                ImGui.setCursorScreenPos(x, buttonY)
                Widgets.iconToggle("lock", if (state.locked) Icon.LOCK else Icon.UNLOCK, state.locked, size, if (state.locked) "Unlock the camera track" else "Lock the camera track so keyframes cannot be dragged")
                    ?.let { session.execute(SetLaneState(lane.kind, state.copy(locked = it))) }
                if (ImGui.isItemActive()) activeHeader = lane.kind
                x -= size + EditorFonts.px(2f)
                used += size + EditorFonts.px(2f)
            }
            if (lane.muteable && (reveal || state.muted)) {
                ImGui.setCursorScreenPos(x, buttonY)
                Widgets.iconToggle("eye", if (state.muted) Icon.EYE_OFF else Icon.EYE, !state.muted, size, if (state.muted) "Enable the ${lane.label.lowercase()} track" else "Disable the ${lane.label.lowercase()} track without deleting its keyframes")
                    ?.let { session.execute(SetLaneState(lane.kind, state.copy(muted = !it))) }
                if (ImGui.isItemActive()) activeHeader = lane.kind
                x -= size + EditorFonts.px(2f)
                used += size + EditorFonts.px(2f)
            }
            if (lane.kind != LaneKind.PLAYERS && lane.kind != LaneKind.WORLD) {
                val count = trailing(session, lane)
                EditorFonts.with(EditorFonts.small) {
                    val countWidth = if (count.isEmpty()) 0f else Widgets.textWidth(count)
                    if (count.isNotEmpty()) list.addText(x + size - countWidth - EditorFonts.px(2f), top + (lane.rowHeight - ImGui.getFontSize()) / 2f, EditorTheme.TEXT_DIM.u32, count)
                    used += countWidth + EditorFonts.px(6f)
                }
                EditorFonts.with(EditorFonts.smallMedium) {
                    val labelX = headerX + EditorFonts.px(31f)
                    val available = originX - used - labelX - EditorFonts.px(4f)
                    list.addText(labelX, top + (lane.rowHeight - ImGui.getFontSize()) / 2f, if (state.muted) EditorTheme.TEXT_DIM.u32 else EditorTheme.TEXT.u32, Widgets.clip(lane.label, available))
                }
                if (lane.kind == LaneKind.EVENTS && !session.events.complete) {
                    val barY = top + height - EditorFonts.px(4f)
                    val barLeft = headerX + EditorFonts.px(12f)
                    val barRight = originX - EditorFonts.px(12f)
                    list.addRectFilled(barLeft, barY, barRight, barY + EditorFonts.px(2f), EditorTheme.CONTROL.u32)
                    list.addRectFilled(barLeft, barY, barLeft + (barRight - barLeft) * session.events.progress.toFloat(), barY + EditorFonts.px(2f), EditorTheme.ACCENT_TEXT.u32)
                }
            }
        } finally {
            ImGui.popID()
        }
    }

    private fun trailing(session: EditorSession, lane: TrackSpec): String = when (lane.kind) {
        LaneKind.EVENTS -> session.events.let { if (it.complete) "${it.events.size}" else "${(it.progress * 100).toInt()}%" }
        LaneKind.MOMENTS -> {
            val kept = session.project.moments.size
            val detected = gameLanes.moments(session).size - kept
            if (detected > 0) "$kept + $detected" else "$kept"
        }

        else -> cache.count(lane.kind).let { if (it == 0) "" else "$it" }
    }

    private fun addAtPlayhead(session: EditorSession, lane: TrackSpec) {
        val nanos = session.playheadNanos
        when (lane.kind) {
            LaneKind.CAMERA -> actions.addCameraKeyframe(session, nanos)
            LaneKind.VIEW -> actions.addViewKeyframe(session, nanos)
            LaneKind.TEXTURE_PACK -> actions.addPackKeyframe(session, nanos)
            LaneKind.TIMELAPSE -> actions.addTimelapse(session, nanos)
            LaneKind.MARKERS -> actions.addMarker(session, nanos)
            LaneKind.MOMENTS -> gameLanes.addManual(session, nanos)
            LaneKind.CLIPS -> actions.clipFromInOut(session)
            else -> lane.valueLane?.let { actions.addValueKeyframe(session, it, nanos) }
        }
    }

    private fun addTrackButton(view: TimelineView) {
        val hidden = TrackSpec.ALL.filter { !visible(it, view) }
        val height = EditorFonts.px(20f)
        ImGui.setCursorScreenPos(geometry.headerX + EditorFonts.px(6f), geometry.rulerTop + (RULER_HEIGHT - height) / 2f)
        if (Widgets.iconLabelButton(
                "add-track",
                Icon.PLUS,
                "Track",
                0f,
                Widgets.ButtonStyle.GHOST,
                hidden.isNotEmpty(),
                if (hidden.isEmpty()) "Every track is shown" else "Add a track: speed ramps, FOV, focus, time of day, view switches, texture packs, players, world and moments",
                height
            )
        ) ImGui.openPopup("add-track")
        if (Widgets.beginPopup("add-track")) {
            menus.addTrack(view, hidden)
            Widgets.endPopup()
        }
    }

    private fun reorder(from: LaneKind, to: LaneKind) {
        val order = context.ui.laneOrder.toMutableList()
        val fromIndex = order.indexOf(from)
        val toIndex = order.indexOf(to)
        if (fromIndex < 0 || toIndex < 0) return
        order.removeAt(fromIndex)
        order.add(toIndex, from)
        context.ui.laneOrder = order
    }

    private fun hoverFeedback(session: EditorSession, mouseX: Float, mouseY: Float) {
        if (mouseX < geometry.originX) return
        if (mouseY >= navTop()) {
            ImGui.setMouseCursor(ImGuiMouseCursor.ResizeEW)
            return
        }
        if (scrollbarHit(mouseX, mouseY)) return
        if (mouseY < geometry.tracksTop) {
            val handle = handleAt(session, mouseX, mouseY)
            ImGui.setMouseCursor(ImGuiMouseCursor.ResizeEW)
            if (handle == DragKind.IN_POINT) TimelineTooltip.simple(Icon.MARK_IN, EditorTheme.SELECTION, "In point", TimeFormat.clock(actions.inPoint(session)), listOf("Drag to move", "I sets it at the playhead"))
            else if (handle == DragKind.OUT_POINT) TimelineTooltip.simple(Icon.MARK_OUT, EditorTheme.SELECTION, "Out point", TimeFormat.clock(actions.outPoint(session)), listOf("Drag to move", "O sets it at the playhead"))
            return
        }
        val lane = geometry.laneAt(mouseY) ?: return
        lanes.hover(session, lane, lanes.hovered, mouseX, mouseY)
    }

    private fun handleAt(session: EditorSession, mouseX: Float, mouseY: Float): DragKind? {
        if (mouseY < geometry.rulerTop + EditorFonts.px(14f) || mouseY >= geometry.tracksTop) return null
        val inX = geometry.xAt(actions.inPoint(session))
        val outX = geometry.xAt(actions.outPoint(session))
        val grab = HANDLE_WIDTH / 2f + EditorFonts.px(3f)
        val inDistance = abs(mouseX - inX)
        val outDistance = abs(mouseX - outX)
        if (inDistance <= grab && inDistance <= outDistance) return DragKind.IN_POINT
        if (outDistance <= grab) return DragKind.OUT_POINT
        return null
    }

    private fun input(session: EditorSession, view: TimelineView, hovered: Boolean, mouseX: Float, mouseY: Float, nowNanos: Long) {
        val io = ImGui.getIO()
        if (drag == DragKind.REORDER && !ImGui.isMouseDown(ImGuiMouseButton.Left)) {
            drag = DragKind.NONE
            reorderKind = null
        }
        if (hovered && drag == DragKind.NONE) {
            val wheel = io.mouseWheel
            val wheelH = io.mouseWheelH
            if (wheel != 0f) {
                when {
                    io.keyCtrl -> scrollBy(-wheel * EditorFonts.px(40f))
                    io.keyShift -> view.offsetNanos -= (wheel * geometry.visibleNanos * 0.1).toLong()
                    else -> zoomAround(view, 1.25.pow(wheel.toDouble()), geometry.nanosAt(mouseX))
                }
            }
            if (wheelH != 0f) view.offsetNanos += (wheelH * geometry.visibleNanos * 0.1).toLong()
            view.offsetNanos = view.offsetNanos.coerceIn(0L, maxOf(0L, duration - geometry.visibleNanos))
        }
        if (hovered && drag == DragKind.NONE && ImGui.isMouseClicked(ImGuiMouseButton.Middle)) {
            drag = DragKind.PAN
            dragStartX = mouseX
            dragOriginNanos = view.offsetNanos
        }
        if (hovered && drag == DragKind.NONE && ImGui.isMouseDoubleClicked(ImGuiMouseButton.Left) && doubleClick(session, mouseX, mouseY)) return
        if (hovered && drag == DragKind.NONE && ImGui.isMouseClicked(ImGuiMouseButton.Left)) press(session, view, mouseX, mouseY)
        if (drag == DragKind.NONE || drag == DragKind.REORDER) return
        val button = if (drag == DragKind.PAN) ImGuiMouseButton.Middle else ImGuiMouseButton.Left
        if (ImGui.isMouseDown(button)) update(session, view, mouseX, mouseY, nowNanos)
        if (ImGui.isMouseReleased(button)) release(session, mouseX)
    }

    private fun doubleClick(session: EditorSession, mouseX: Float, mouseY: Float): Boolean {
        val replay = session.replay ?: return false
        if (mouseY < geometry.tracksTop || mouseY >= geometry.tracksBottom) return false
        val lane = geometry.laneAt(mouseY) ?: return false
        val item = lanes.itemAt(session, mouseX, mouseY)
        val project = session.project
        when (item) {
            is TimelineItem.CameraKey -> {
                replay.seek(item.nanos)
                session.selection = Selection(keyframeTimes = setOf(item.nanos))
            }

            is TimelineItem.ValueKeyItem -> replay.seek(item.key.nanos)
            is TimelineItem.ViewKey -> replay.seek(item.nanos)
            is TimelineItem.PackKey -> replay.seek(item.nanos)
            is TimelineItem.ClipItem -> project.clip(item.id)?.let { actions.playClip(session, it) }
            is TimelineItem.MarkerItem -> project.marker(item.id)?.let { replay.seek(it.nanos) }
            is TimelineItem.TimelapseItem -> project.timelapse(item.id)?.let { replay.seek(it.nanos) }
            is TimelineItem.MomentItem -> gameLanes.moments(session).firstOrNull { it.id == item.id }?.let { moment ->
                session.execute(SetInOutPoints(moment.nanos, moment.endNanos))
                replay.seek(moment.nanos)
                replay.play()
                session.selection = Selection(momentIds = setOf(moment.id))
            }

            null -> {
                val nanos = snap(geometry.nanosAt(mouseX).coerceIn(0L, duration), Selection.NONE, true)
                when (lane.kind) {
                    LaneKind.MARKERS -> actions.addMarker(session, nanos)
                    LaneKind.TIMELAPSE -> actions.addTimelapse(session, nanos)
                    LaneKind.VIEW -> actions.addViewKeyframe(session, nanos)
                    LaneKind.TEXTURE_PACK -> actions.addPackKeyframe(session, nanos)
                    LaneKind.MOMENTS -> gameLanes.addManual(session, nanos)
                    LaneKind.CAMERA -> {
                        replay.seek(nanos)
                        actions.addCameraKeyframe(session, nanos)
                    }

                    LaneKind.PLAYERS -> gameLanes.togglePlayer(mouseY - geometry.laneTop(lane.kind), lane.rowHeight)
                    else -> lane.valueLane?.let { actions.addValueKeyframe(session, it, nanos) } ?: return false
                }
            }
        }
        drag = DragKind.NONE
        return true
    }

    private fun press(session: EditorSession, view: TimelineView, mouseX: Float, mouseY: Float) {
        val io = ImGui.getIO()
        val additive = io.keyCtrl || io.keyShift
        dragStartX = mouseX
        dragStartY = mouseY
        dragMoved = false
        dragAdditiveToggle = false
        dragItem = null
        if (mouseY >= navTop()) {
            val left = navX(view.offsetNanos)
            val right = navX(view.offsetNanos + geometry.visibleNanos)
            navOriginOffset = view.offsetNanos
            navOriginVisible = geometry.visibleNanos
            drag = when {
                mouseX >= left - 4f && mouseX <= left + 5f -> DragKind.NAV_LEFT
                mouseX >= right - 5f && mouseX <= right + 4f -> DragKind.NAV_RIGHT
                mouseX > left && mouseX < right -> DragKind.NAV_THUMB
                else -> {
                    view.offsetNanos = (navNanos(mouseX) - geometry.visibleNanos / 2).coerceIn(0L, maxOf(0L, duration - geometry.visibleNanos))
                    navOriginOffset = view.offsetNanos
                    DragKind.NAV_THUMB
                }
            }
            return
        }
        if (scrollbarHit(mouseX, mouseY)) {
            drag = DragKind.SCROLLBAR
            scrollOrigin = geometry.scrollY
            return
        }
        if (mouseY < geometry.tracksTop) {
            val handle = handleAt(session, mouseX, mouseY)
            if (handle != null) {
                drag = handle
                dragCurrentNanos = if (handle == DragKind.IN_POINT) actions.inPoint(session) else actions.outPoint(session)
                return
            }
            beginScrub(session, mouseX)
            return
        }
        if (mouseY >= geometry.tracksBottom) return
        val lane = geometry.laneAt(mouseY)
        val item = if (lane != null) lanes.itemAt(session, mouseX, mouseY) else null
        if (item != null) {
            pressItem(session, item, mouseX, additive, io.keyAlt)
            return
        }
        if (lane != null) {
            when (lane.kind) {
                LaneKind.EVENTS -> lanes.eventAt(session, mouseX)?.let {
                    session.replay?.seek(it.nanos)
                    return
                }

                LaneKind.PLAYERS -> if (gameLanes.clickPlayers(session, mouseX, mouseY - geometry.laneTop(lane.kind), lane.rowHeight)) return
                LaneKind.WORLD -> if (gameLanes.clickWorld(session, mouseX, mouseY - geometry.laneTop(lane.kind), lane.rowHeight)) return
                else -> Unit
            }
        }
        drag = DragKind.BOX
    }

    private fun pressItem(session: EditorSession, item: TimelineItem, mouseX: Float, additive: Boolean, duplicate: Boolean) {
        val selected = session.selection.has(item)
        when {
            selected && additive -> dragAdditiveToggle = true
            selected -> Unit
            additive -> session.selection = session.selection.with(item, true)
            else -> session.selection = session.selection.with(item, false)
        }
        context.selectedEntityId = null
        dragItem = item
        if (item is TimelineItem.ClipItem) {
            val clip = session.project.clip(item.id)
            val edge = if (clip != null) lanes.clipEdge(clip, mouseX) else 0
            if (clip != null && edge != 0) {
                drag = if (edge < 0) DragKind.CLIP_START else DragKind.CLIP_END
                dragClip = clip.id
                lanes.trimClip = clip.id
                lanes.trimStart = clip.startNanos
                lanes.trimEnd = clip.endNanos
                return
            }
        }
        if (item is TimelineItem.CameraKey && session.project.lane(LaneKind.CAMERA).locked) {
            drag = DragKind.NONE
            context.status("The camera track is locked")
            return
        }
        if (item is TimelineItem.MomentItem) {
            drag = DragKind.NONE
            return
        }
        drag = DragKind.MOVE
        dragAnchorNanos = itemTime(session, item) ?: geometry.nanosAt(mouseX)
        dragOriginNanos = geometry.nanosAt(mouseX)
        dragCurrentNanos = dragOriginNanos
        lanes.duplicating = duplicate && session.selection.keyframeTimes.isNotEmpty()
    }

    private fun itemTime(session: EditorSession, item: TimelineItem): Long? {
        val project = session.project
        return when (item) {
            is TimelineItem.CameraKey -> item.nanos
            is TimelineItem.ValueKeyItem -> item.key.nanos
            is TimelineItem.ViewKey -> item.nanos
            is TimelineItem.PackKey -> item.nanos
            is TimelineItem.MarkerItem -> project.marker(item.id)?.nanos
            is TimelineItem.TimelapseItem -> project.timelapse(item.id)?.nanos
            is TimelineItem.ClipItem -> project.clip(item.id)?.startNanos
            is TimelineItem.MomentItem -> project.moment(item.id)?.nanos
        }
    }

    private fun beginScrub(session: EditorSession, mouseX: Float) {
        drag = DragKind.SCRUB
        scrubTarget = snap(geometry.nanosAt(mouseX), Selection.NONE, false).coerceIn(0L, duration)
        lastScrubSeekNanos = 0L
        session.replay?.seek(scrubTarget)
    }

    private fun update(session: EditorSession, view: TimelineView, mouseX: Float, mouseY: Float, nowNanos: Long) {
        if (!dragMoved && (abs(mouseX - dragStartX) > DRAG_THRESHOLD || abs(mouseY - dragStartY) > DRAG_THRESHOLD)) dragMoved = true
        when (drag) {
            DragKind.PAN -> view.offsetNanos = (dragOriginNanos - ((mouseX - dragStartX) / geometry.width * geometry.visibleNanos).toLong()).coerceIn(0L, maxOf(0L, duration - geometry.visibleNanos))
            DragKind.SCRUB -> {
                edgeScroll(view, mouseX)
                scrubTarget = snap(geometry.nanosAt(mouseX), Selection.NONE, false).coerceIn(0L, duration)
                val interval = maxOf(MIN_SCRUB_INTERVAL, (session.replay?.lastSeekDurationNanos ?: 0L) * 3 / 2)
                if (nowNanos - lastScrubSeekNanos >= interval) {
                    lastScrubSeekNanos = nowNanos
                    session.replay?.seek(scrubTarget)
                }
            }

            DragKind.IN_POINT -> dragCurrentNanos = snap(geometry.nanosAt(mouseX), Selection.NONE, true).coerceIn(0L, actions.outPoint(session))
            DragKind.OUT_POINT -> dragCurrentNanos = snap(geometry.nanosAt(mouseX), Selection.NONE, true).coerceIn(actions.inPoint(session), duration)
            DragKind.MOVE -> if (dragMoved) {
                edgeScroll(view, mouseX)
                lanes.duplicating = ImGui.getIO().keyAlt && session.selection.keyframeTimes.isNotEmpty()
                val raw = dragAnchorNanos + (geometry.nanosAt(mouseX) - dragOriginNanos)
                val delta = snap(raw, session.selection, true) - dragAnchorNanos
                dragCurrentNanos = dragOriginNanos + maxOf(-minSelectedTime(session), delta)
            }

            DragKind.CLIP_START -> lanes.trimStart = snap(geometry.nanosAt(mouseX), session.selection, true).coerceIn(0L, lanes.trimEnd - Nanos.PER_TICK)
            DragKind.CLIP_END -> lanes.trimEnd = snap(geometry.nanosAt(mouseX), session.selection, true).coerceIn(lanes.trimStart + Nanos.PER_TICK, duration)
            DragKind.BOX -> if (dragMoved) edgeScroll(view, mouseX)
            DragKind.NAV_THUMB -> view.offsetNanos = (navOriginOffset + navNanos(mouseX) - navNanos(dragStartX)).coerceIn(0L, maxOf(0L, duration - geometry.visibleNanos))
            DragKind.NAV_LEFT -> {
                val end = navOriginOffset + navOriginVisible
                val start = (navOriginOffset + navNanos(mouseX) - navNanos(dragStartX)).coerceIn(0L, end - MIN_VISIBLE)
                setVisible(view, start, end - start)
            }

            DragKind.NAV_RIGHT -> {
                val end = (navOriginOffset + navOriginVisible + navNanos(mouseX) - navNanos(dragStartX)).coerceIn(navOriginOffset + MIN_VISIBLE, duration)
                setVisible(view, navOriginOffset, end - navOriginOffset)
            }

            DragKind.SCROLLBAR -> {
                val viewport = geometry.tracksBottom - geometry.tracksTop
                val overflow = geometry.contentHeight - viewport
                if (overflow > 0f) geometry.scrollY = (scrollOrigin + (mouseY - dragStartY) * (geometry.contentHeight / viewport)).coerceIn(0f, overflow)
            }

            else -> Unit
        }
    }

    private fun minSelectedTime(session: EditorSession): Long {
        val selection = session.selection
        val project = session.project
        var min = Long.MAX_VALUE
        for (time in selection.keyframeTimes) min = minOf(min, time)
        for (key in selection.valueKeys) min = minOf(min, key.nanos)
        for (time in selection.viewTimes) min = minOf(min, time)
        for (time in selection.packTimes) min = minOf(min, time)
        for (id in selection.markerIds) project.marker(id)?.let { min = minOf(min, it.nanos) }
        for (id in selection.timelapseIds) project.timelapse(id)?.let { min = minOf(min, it.nanos) }
        for (id in selection.clipIds) project.clip(id)?.let { min = minOf(min, it.startNanos) }
        for (id in selection.momentIds) project.moment(id)?.let { min = minOf(min, it.nanos) }
        return if (min == Long.MAX_VALUE) 0L else min
    }

    private fun edgeScroll(view: TimelineView, mouseX: Float) {
        val margin = EditorFonts.px(24f)
        val step = (geometry.visibleNanos * 0.02).toLong()
        val delta = when {
            mouseX < geometry.originX + margin -> -step
            mouseX > geometry.right - margin -> step
            else -> 0L
        }
        if (delta != 0L) view.offsetNanos = (view.offsetNanos + delta).coerceIn(0L, maxOf(0L, duration - geometry.visibleNanos))
    }

    private fun release(session: EditorSession, mouseX: Float) {
        when (drag) {
            DragKind.SCRUB -> if (scrubTarget >= 0L) session.replay?.seek(scrubTarget)
            DragKind.IN_POINT -> if (dragCurrentNanos != actions.inPoint(session)) session.execute(SetInOutPoints(dragCurrentNanos, actions.outPoint(session)))
            DragKind.OUT_POINT -> if (dragCurrentNanos != actions.outPoint(session)) session.execute(SetInOutPoints(actions.inPoint(session), dragCurrentNanos))
            DragKind.MOVE -> {
                val item = dragItem
                when {
                    dragMoved -> actions.moveSelection(session, dragCurrentNanos - dragOriginNanos, lanes.duplicating)
                    item != null && dragAdditiveToggle -> session.selection = session.selection.without(item)
                    item != null && session.selection.count > 1 -> session.selection = session.selection.with(item, false)
                }
            }

            DragKind.CLIP_START, DragKind.CLIP_END -> dragClip?.let { id ->
                session.project.clip(id)?.let { actions.trimClip(session, it, lanes.trimStart, lanes.trimEnd) }
            }

            DragKind.BOX -> if (dragMoved) boxSelect(session) else {
                if (!ImGui.getIO().keyCtrl && !ImGui.getIO().keyShift) {
                    actions.clearSelection(session)
                    context.selectedEntityId = null
                }
                session.replay?.seek(snap(geometry.nanosAt(mouseX), Selection.NONE, false).coerceIn(0L, duration))
            }

            else -> Unit
        }
        drag = DragKind.NONE
        dragItem = null
        dragClip = null
        dragMoved = false
        dragAdditiveToggle = false
        lanes.trimClip = null
        lanes.duplicating = false
        scrubTarget = -1L
        geometry.dragActive = false
        geometry.dragDeltaNanos = 0L
    }

    private fun boxSelect(session: EditorSession) {
        val x1 = minOf(dragStartX, ImGui.getMousePosX())
        val x2 = maxOf(dragStartX, ImGui.getMousePosX())
        val y1 = minOf(dragStartY, ImGui.getMousePosY())
        val y2 = maxOf(dragStartY, ImGui.getMousePosY())
        val from = geometry.nanosAt(x1)
        val to = geometry.nanosAt(x2)
        val additive = ImGui.getIO().keyCtrl || ImGui.getIO().keyShift
        var selection = if (additive) session.selection else Selection.NONE
        for (lane in geometry.lanes) {
            if (!lane.selectable) continue
            val top = geometry.laneTop(lane.kind)
            if (y2 < top || y1 > top + geometry.laneHeight(lane.kind)) continue
            selection = if (lane.kind == LaneKind.MOMENTS) selection.plus(gameLanes.moments(session).filter { it.endNanos >= from && it.nanos <= to }.map { TimelineItem.MomentItem(it.id) })
            else selection.plus(cache.itemsBetween(lane.kind, from, to))
        }
        session.selection = selection
        if (!selection.isEmpty) context.selectedEntityId = null
    }

    private fun prepareContext(session: EditorSession, mouseX: Float, mouseY: Float) {
        contextNanos = geometry.nanosAt(mouseX).coerceIn(0L, duration)
        contextLane = geometry.laneAt(mouseY)
        val item = if (mouseY >= geometry.tracksTop && mouseY < geometry.tracksBottom) lanes.itemAt(session, mouseX, mouseY) else null
        contextItem = item
        if (item != null) {
            if (!session.selection.has(item)) session.selection = session.selection.with(item, false)
            menus.prepare(session, item)
        }
    }

    private fun keyboard(session: EditorSession, hovered: Boolean) {
        if (!(ImGui.isWindowFocused() || hovered) || ImGui.getIO().wantTextInput) return
        val io = ImGui.getIO()
        if (ImGui.isKeyPressed(ImGuiKey.Delete, false) || ImGui.isKeyPressed(ImGuiKey.Backspace, false)) actions.deleteSelection(session)
        if (hovered && io.keyCtrl && ImGui.isKeyPressed(ImGuiKey.A, false)) actions.selectAll(session, cache, geometry.lanes)
    }

    private fun scrollBy(delta: Float) {
        val viewport = geometry.tracksBottom - geometry.tracksTop
        geometry.scrollY = (geometry.scrollY + delta).coerceIn(0f, maxOf(0f, geometry.contentHeight - viewport))
    }

    private fun follow(positionNanos: Long, view: TimelineView) {
        val visible = geometry.visibleNanos
        if (positionNanos < view.offsetNanos || positionNanos > view.offsetNanos + visible * 9 / 10) {
            view.offsetNanos = (positionNanos - visible / 10).coerceIn(0L, maxOf(0L, duration - visible))
        }
    }

    private fun setVisible(view: TimelineView, offset: Long, visible: Long) {
        val clamped = visible.coerceIn(MIN_VISIBLE, duration)
        view.zoom = duration.toDouble() / clamped
        geometry.visibleNanos = clamped
        view.offsetNanos = offset.coerceIn(0L, maxOf(0L, duration - clamped))
    }

    private fun zoomAround(view: TimelineView, factor: Double, anchorNanos: Long) {
        val before = geometry.visibleNanos
        view.zoom = (view.zoom * factor).coerceIn(1.0, duration.toDouble() / MIN_VISIBLE)
        val after = maxOf(1L, (duration / view.zoom).toLong())
        val ratio = (anchorNanos - view.offsetNanos).toDouble() / before
        view.offsetNanos = (anchorNanos - (after * ratio).toLong()).coerceIn(0L, maxOf(0L, duration - after))
        geometry.visibleNanos = after
        geometry.offsetNanos = view.offsetNanos
    }

    private fun snap(nanos: Long, exclude: Selection, grid: Boolean): Long {
        val view = context.timeline
        if (!view.snapToTicks || ImGui.getIO().keyShift) return nanos
        val frame = view.frameNanos()
        var best = if (grid) Math.round(nanos.toDouble() / frame) * frame else nanos
        var bestDistance = (SNAP_PIXELS * geometry.nanosPerPixel).toLong()
        val session = context.session
        val candidates = ArrayList<Long>(4)
        context.replay?.let { candidates += it.positionNanos }
        if (session != null) {
            candidates += actions.inPoint(session)
            candidates += actions.outPoint(session)
        }
        for (candidate in candidates) {
            val distance = abs(candidate - nanos)
            if (distance <= bestDistance) {
                best = candidate
                bestDistance = distance
            }
        }
        for (candidate in cache.snapTimes(exclude)) {
            val distance = abs(candidate - nanos)
            if (distance <= bestDistance) {
                best = candidate
                bestDistance = distance
            }
        }
        return best
    }

    private companion object {
        val INSPECTABLE = setOf(
            LaneKind.CAMERA, LaneKind.SPEED, LaneKind.FOV, LaneKind.FOCUS, LaneKind.TIME_OF_DAY, LaneKind.SHAKE,
            LaneKind.SHAKE_FREQUENCY, LaneKind.FREEZE, LaneKind.VIEW, LaneKind.TEXTURE_PACK
        )
        val SEGMENT_COLORS = listOf(EditorTheme.ACCENT_TEXT, EditorTheme.WARNING, EditorTheme.SUCCESS, EditorTheme.KEYFRAME_BEZIER)
        val FRAME_STEPS = longArrayOf(1, 2, 5, 10, 15, 30)
        val SECOND_STEPS = longArrayOf(
            Nanos.ofSeconds(1), Nanos.ofSeconds(2), Nanos.ofSeconds(5), Nanos.ofSeconds(10), Nanos.ofSeconds(15), Nanos.ofSeconds(30),
            Nanos.ofSeconds(60), Nanos.ofSeconds(120), Nanos.ofSeconds(300), Nanos.ofSeconds(600), Nanos.ofSeconds(1800),
        )
        val RULER_HEIGHT: Float get() = EditorFonts.px(30f)
        val HEADER_WIDTH: Float get() = EditorFonts.px(176f)
        val NAV_HEIGHT: Float get() = EditorFonts.px(10f)
        val NAV_GAP: Float get() = EditorFonts.px(6f)
        val TOOL_SIZE: Float get() = EditorFonts.px(26f)
        val HANDLE_WIDTH: Float get() = EditorFonts.px(6f)
        val HANDLE_HEIGHT: Float get() = EditorFonts.px(11f)
        val SNAP_PIXELS: Float get() = EditorFonts.px(6f)
        val SCROLLBAR_WIDTH: Float get() = EditorFonts.px(5f)
        val LABEL_SPACING: Float get() = EditorFonts.px(84f)
        val DRAG_THRESHOLD: Float get() = EditorFonts.px(4f)
        val MIN_VISIBLE: Long = Nanos.ofMillis(250)
        val MIN_SCRUB_INTERVAL = Nanos.ofMillis(4)
    }
}
