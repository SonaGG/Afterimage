package gg.sona.recast.editor.imgui

import gg.sona.recast.camera.CameraKeyframe
import gg.sona.recast.camera.CameraMode
import gg.sona.recast.camera.CameraSettings
import gg.sona.recast.camera.track.Keyframe
import gg.sona.recast.camera.track.SegmentMode
import gg.sona.recast.clip.Clip
import gg.sona.recast.core.time.Nanos
import gg.sona.recast.editor.*
import gg.sona.recast.editor.commands.*
import gg.sona.recast.replay.session.ReplaySession
import imgui.ImDrawList
import imgui.ImGui
import imgui.flag.ImGuiKey
import imgui.flag.ImGuiMouseButton
import imgui.flag.ImGuiMouseCursor
import imgui.flag.ImGuiWindowFlags
import imgui.type.ImString
import java.util.*
import kotlin.math.ln

class TimelinePanel(private val context: EditorContext) :
    AbstractPanel("Timeline", DockArea.BOTTOM, Icon.CLOCK, flags = ImGuiWindowFlags.NoScrollbar) {

    private enum class DragKind { NONE, SCRUB, IN_POINT, OUT_POINT, KEYFRAMES, VALUE_KEY, VIEW_KEY, MARKER, TIMELAPSE, CLIP_BODY, CLIP_START, CLIP_END, PAN, BOX, NAV_THUMB, NAV_LEFT, NAV_RIGHT }

    private class PoseKey(val entityId: Int, val timeNanos: Long, val parts: Int)

    private class Lane(
        val kind: LaneKind,
        val label: String,
        val baseHeight: Float,
        val strip: EditorTheme.Rgb,
        val rows: (() -> Int)? = null,
    ) {
        val rowHeight: Float get() = EditorFonts.px(baseHeight)
        val height: Float get() = rowHeight * maxOf(1, rows?.invoke() ?: 1)
    }

    private var drag = DragKind.NONE
    private var dragId: Any? = null
    private var dragTimes: Set<Long> = emptySet()
    private var dragDelta = 0L
    private var dragDuplicate = false
    private var dragOriginNanos = 0L
    private var dragStartMouseX = 0f
    private var dragStartMouseY = 0f
    private var dragCurrentNanos = 0L
    private var dragClipStart = 0L
    private var dragClipEnd = 0L
    private var navOriginOffset = 0L
    private var navOriginVisible = 0L
    private var scrubTarget = -1L
    private var lastScrubSeekNanos = 0L
    private var contextNanos = 0L
    private var contextKeyframe: Long? = null
    private var contextValue: ValueKey? = null
    private var contextView: Long? = null
    private var dragLane: ValueLane? = null
    private var contextClip: UUID? = null
    private var contextMarker: UUID? = null
    private var contextTimelapse: UUID? = null
    private var contextPose: PoseKey? = null
    private var contextLane: LaneKind? = null
    private val renameBuffer = ImString("", 64)
    private val valueHolder = FloatArray(1)

    private var originX = 0f
    private var originY = 0f
    private var headerX = 0f
    private var width = 1f
    private var tracksHeight = 0f
    private var canvasHeight = 0f
    private var visibleNanos = 1L
    private var duration = 1L

    private var cacheSession: EditorSession? = null
    private var cacheVersion = -1L
    private var keyframes: List<CameraKeyframe> = emptyList()
    private var keyTimes: LongArray = LongArray(0)
    private var valueKeys: Map<ValueLane, List<Keyframe<Double>>> = emptyMap()
    private var viewKeys: List<Keyframe<ViewState>> = emptyList()
    private var clips: List<Clip> = emptyList()
    private var markers: List<TimelineMarker> = emptyList()
    private var timelapses: List<TimelapseMark> = emptyList()
    private var poseKeys: List<PoseKey> = emptyList()
    private val geometry = TimelineGeometry()
    private val gameLanes = GameLanes(context, geometry)
    private val allLanes: List<Lane> = BASE_LANES + listOf(
        Lane(LaneKind.PLAYERS, "Players", 20f, EditorTheme.ACCENT_TEXT) { gameLanes.playerRowCount() },
        Lane(LaneKind.WORLD, "World", 18f, EditorTheme.KEYFRAME_LINEAR) { gameLanes.worldRows().size },
        Lane(LaneKind.MOMENTS, "Moments", 26f, EditorTheme.KEYFRAME_HOLD),
    )
    private var lanes: List<Lane> = allLanes
    private var contextMoment: UUID? = null
    private var contextHeader: LaneKind? = null

    override fun content(frame: FrameContext) {
        val session = context.session
        val replay = session?.replay
        if (session == null || replay == null) {
            Widgets.emptyState(
                "No replay open",
                "Open a recording from the Project panel to edit its timeline",
                Icon.FILM
            )
            return
        }
        val view = context.timeline
        duration = maxOf(1L, replay.durationNanos)
        refreshCaches(session)
        toolbar(session, view)

        val order = context.ui.laneOrder
        gameLanes.prepare(session)
        lanes = allLanes.sortedBy { order.indexOf(it.kind) }.filter { laneVisible(it.kind, view) }
        tracksHeight = lanes.sumOf { it.height.toDouble() }.toFloat()
        headerX = ImGui.getCursorScreenPosX()
        originX = headerX + HEADER_WIDTH
        originY = ImGui.getCursorScreenPosY()
        width = maxOf(1f, ImGui.getContentRegionAvailX() - HEADER_WIDTH)
        val available = ImGui.getContentRegionAvailY()
        canvasHeight = maxOf(RULER_HEIGHT + tracksHeight + NAV_HEIGHT + NAV_GAP, available - 2f)
        val totalHeight = canvasHeight
        visibleNanos = maxOf(1L, (duration / view.zoom).toLong())
        if (view.followPlayhead && replay.playing && drag == DragKind.NONE) followPlayhead(replay.positionNanos, view)
        view.offsetNanos = view.offsetNanos.coerceIn(0L, maxOf(0L, duration - visibleNanos))
        geometry.headerX = headerX
        geometry.originX = originX
        geometry.width = width
        geometry.offsetNanos = view.offsetNanos
        geometry.visibleNanos = visibleNanos
        geometry.duration = duration

        val drawList = ImGui.getWindowDrawList()
        drawList.addRectFilled(
            headerX,
            originY,
            originX + width,
            originY + maxOf(totalHeight, available),
            EditorTheme.PANEL_SUNKEN.u32
        )
        drawHeaders(drawList, session)
        headerWidgets(session)
        headerMenus(session, view)
        addTrackButton(view)

        ImGui.setCursorScreenPos(originX, originY)
        ImGui.invisibleButton("timeline-canvas", width, totalHeight)
        val hovered = ImGui.isItemHovered()
        val active = ImGui.isItemActive()
        drawList.pushClipRect(originX, originY, originX + width, originY + totalHeight, true)
        drawLaneBackgrounds(drawList)
        drawWorkArea(drawList, session)
        drawRuler(drawList, view)
        drawCameraLane(drawList, session, frame.nowNanos)
        for (lane in ValueLane.entries) if (lanes.any { it.kind == lane.kind }) drawValueLane(drawList, session, lane)
        if (lanes.any { it.kind == LaneKind.VIEW }) drawViewLane(drawList, session)
        drawClipLane(drawList, session)
        drawMarkerLane(drawList, session)
        drawTimelapseLane(drawList, session)
        drawPoseLane(drawList, session)
        drawEventLane(drawList, session)
        lanes.firstOrNull { it.kind == LaneKind.PLAYERS }
            ?.let { gameLanes.drawPlayers(drawList, laneTop(it.kind), it.rowHeight) }
        lanes.firstOrNull { it.kind == LaneKind.WORLD }
            ?.let { gameLanes.drawWorld(drawList, laneTop(it.kind), it.rowHeight) }
        lanes.firstOrNull { it.kind == LaneKind.MOMENTS }
            ?.let { gameLanes.drawMoments(drawList, session, laneTop(it.kind), it.height) }
        drawInOutHandles(drawList, session)
        val playheadNanos = if (drag == DragKind.SCRUB && scrubTarget >= 0L) scrubTarget else replay.positionNanos
        drawPlayhead(drawList, playheadNanos)
        if (drag == DragKind.BOX) drawBox(drawList)
        if (hovered && drag == DragKind.NONE) drawHoverLine(drawList)
        drawList.popClipRect()
        drawNavigator(drawList, view)

        if (hovered || active || drag != DragKind.NONE) handleInput(session, view, hovered, frame.nowNanos)
        if (hovered && drag == DragKind.NONE) hoverFeedback(session)
        if (hovered && ImGui.isMouseClicked(ImGuiMouseButton.Right)) prepareContext(session)
        if (ImGui.beginPopupContextItem("timeline-context")) {
            contextMenu(session)
            ImGui.endPopup()
        }
        if ((ImGui.isWindowFocused() || hovered) && !ImGui.getIO().wantTextInput && (ImGui.isKeyPressed(
                ImGuiKey.Delete,
                false
            ) || ImGui.isKeyPressed(ImGuiKey.Backspace, false))
        ) deleteSelection(session)
        ImGui.setCursorScreenPos(headerX, originY + totalHeight)
        ImGui.dummy(1f, 1f)
    }

    private fun refreshCaches(session: EditorSession) {
        val version = session.commands.version
        if (session === cacheSession && version == cacheVersion) return
        cacheSession = session
        cacheVersion = version
        keyframes = session.project.camera.keyframes()
        keyTimes = LongArray(keyframes.size) { keyframes[it].timeNanos }
        valueKeys = ValueLane.entries.associateWith { session.project.valueTrack(it).keyframes.toList() }
        viewKeys = session.project.views.keyframes.toList()
        clips = session.project.clips.toList()
        markers = session.project.markers.toList()
        timelapses = session.project.timelapses.toList()
        poseKeys = session.project.poses.flatMap { (id, track) ->
            track.keyframes.map {
                PoseKey(
                    id,
                    it.timeNanos,
                    it.value.parts.size
                )
            }
        }
    }

    private fun toolbar(session: EditorSession, view: TimelineView) {
        val replay = session.replay ?: return
        EditorTheme.pushToolbarStyle()
        try {
            val rowY = ImGui.getCursorScreenPosY()
            val right = ImGui.getCursorScreenPosX() + ImGui.getContentRegionAvailX()
            if (Widgets.iconButton("tl-key", Icon.KEYFRAME_ADD, TOOL_SIZE, "Add camera keyframe at playhead  Ctrl+K"))
                session.keyframeAtPlayhead(context.host.camera.currentPose())
            ImGui.sameLine()
            if (Widgets.iconButton("tl-marker", Icon.MARKER, TOOL_SIZE, "Add marker at playhead  M")) addMarker(session, replay.positionNanos)
            ImGui.sameLine()
            if (Widgets.iconButton("tl-clip", Icon.FILM, TOOL_SIZE, "Create clip from the in and out points")) clipFromInOut(session)
            Widgets.verticalSeparator(TOOL_SIZE)
            if (Widgets.iconButton("tl-in", Icon.MARK_IN, TOOL_SIZE, "Set in point at playhead  I")) session.execute(
                SetInOutPoints(replay.positionNanos, maxOf(replay.positionNanos, outPoint(session)))
            )
            ImGui.sameLine()
            if (Widgets.iconButton("tl-out", Icon.MARK_OUT, TOOL_SIZE, "Set out point at playhead  O")) session.execute(
                SetInOutPoints(minOf(session.project.inPointNanos, replay.positionNanos), replay.positionNanos)
            )
            ImGui.sameLine()
            Widgets.iconToggle("tl-loop", Icon.LOOP, context.loopPlayback, TOOL_SIZE, "Loop between the in and out points")
                ?.let { context.loopPlayback = it }
            val rightWidth = Widgets.lastWidth("tl-right")
            ImGui.sameLine()
            ImGui.setCursorScreenPos(maxOf(ImGui.getCursorScreenPosX(), right - rightWidth), rowY)
            Widgets.measured("tl-right") {
                Widgets.iconToggle("tl-snap", Icon.MAGNET, view.snapToTicks, TOOL_SIZE, "Snap to frames and items  hold Shift to bypass")
                    ?.let { view.snapToTicks = it }
                ImGui.sameLine()
                Widgets.iconToggle("tl-follow", Icon.FOLLOW, view.followPlayhead, TOOL_SIZE, "Follow playhead while playing  F")
                    ?.let { view.followPlayhead = it }
                Widgets.verticalSeparator(TOOL_SIZE)
                if (Widgets.iconButton("tl-zoom-out", Icon.ZOOM_OUT, TOOL_SIZE, "Zoom out  wheel")) zoomAround(view, 1 / 1.5, session.playheadNanos)
                ImGui.sameLine()
                zoomSlider(view, session)
                ImGui.sameLine()
                if (Widgets.iconButton("tl-zoom-in", Icon.ZOOM_IN, TOOL_SIZE, "Zoom in  wheel")) zoomAround(view, 1.5, session.playheadNanos)
                ImGui.sameLine()
                if (Widgets.iconButton("tl-fit", Icon.FIT, TOOL_SIZE, "Fit whole replay")) {
                    view.zoom = 1.0
                    view.offsetNanos = 0L
                }
            }
        } finally {
            EditorTheme.popToolbarStyle()
        }
        ImGui.dummy(0f, 2f)
    }

    private fun zoomSlider(view: TimelineView, session: EditorSession) {
        val width = EditorFonts.px(90f)
        val height = TOOL_SIZE
        val x = ImGui.getCursorScreenPosX()
        val y = ImGui.getCursorScreenPosY()
        ImGui.invisibleButton("tl-zoom", width, height)
        val hovered = ImGui.isItemHovered()
        val active = ImGui.isItemActive()
        val maxZoom = maxOf(1.0, duration.toDouble() / MIN_VISIBLE)
        val t = (Math.log(view.zoom) / Math.log(maxZoom)).toFloat().coerceIn(0f, 1f)
        val list = ImGui.getWindowDrawList()
        val pad = EditorFonts.px(6f)
        val cy = y + height / 2f
        val track = EditorFonts.px(3f)
        list.addRectFilled(x + pad, cy - track / 2f, x + width - pad, cy + track / 2f, EditorTheme.CONTROL_HOVER.u32, track / 2f)
        val kx = x + pad + (width - pad * 2f) * t
        list.addRectFilled(x + pad, cy - track / 2f, kx, cy + track / 2f, EditorTheme.ACCENT.u32, track / 2f)
        list.addCircleFilled(kx, cy, EditorFonts.px(if (active) 6f else 5f), EditorTheme.TEXT.u32, 16)
        if (active) {
            val next = ((ImGui.getMousePosX() - x - pad) / (width - pad * 2f)).coerceIn(0f, 1f)
            val zoom = Math.pow(maxZoom, next.toDouble()).coerceIn(1.0, maxZoom)
            if (zoom != view.zoom) zoomAround(view, zoom / view.zoom, session.playheadNanos)
        }
        if (hovered || active) Widgets.hint(String.format("Zoom %.1fx", view.zoom))
    }

    private fun laneTop(kind: LaneKind): Float {
        var y = originY + RULER_HEIGHT
        for (lane in lanes) {
            if (lane.kind == kind) return y
            y += lane.height
        }
        return y
    }

    private fun laneHeight(kind: LaneKind): Float = lanes.firstOrNull { it.kind == kind }?.height ?: 0f

    private fun laneVisible(kind: LaneKind, view: TimelineView): Boolean = when (kind) {
        LaneKind.CAMERA, LaneKind.CLIPS, LaneKind.MARKERS, LaneKind.EVENTS -> true
        LaneKind.VIEW -> viewKeys.isNotEmpty() || kind in view.shownLanes
        LaneKind.TIMELAPSE -> timelapses.isNotEmpty() || kind in view.shownLanes
        LaneKind.POSE -> poseKeys.isNotEmpty() || kind in view.shownLanes
        LaneKind.PLAYERS -> kind in view.shownLanes || (gameLanes.playerRows.isNotEmpty() && kind !in view.hiddenLanes)
        LaneKind.WORLD -> kind in view.shownLanes || (gameLanes.index != null && kind !in view.hiddenLanes)
        LaneKind.MOMENTS -> kind in view.shownLanes || (context.session?.let {
            gameLanes.moments(it).isNotEmpty()
        } == true && kind !in view.hiddenLanes)

        else -> ValueLane.entries.firstOrNull { it.kind == kind }
            ?.let { valueKeys[it]?.isNotEmpty() == true } == true || kind in view.shownLanes
    }

    private fun laneEmpty(kind: LaneKind): Boolean = when (kind) {
        LaneKind.VIEW -> viewKeys.isEmpty()
        LaneKind.TIMELAPSE -> timelapses.isEmpty()
        LaneKind.POSE -> poseKeys.isEmpty()
        LaneKind.PLAYERS, LaneKind.WORLD, LaneKind.MOMENTS -> false
        else -> ValueLane.entries.firstOrNull { it.kind == kind }?.let { valueKeys[it].isNullOrEmpty() } ?: true
    }

    private fun optional(kind: LaneKind): Boolean = kind !in CORE_LANES

    private fun laneAt(mouseY: Float): Lane? {
        var y = originY + RULER_HEIGHT
        for (lane in lanes) {
            if (mouseY >= y && mouseY < y + lane.height) return lane
            y += lane.height
        }
        return null
    }

    private fun drawHeaders(drawList: ImDrawList, session: EditorSession) {
        drawList.addRectFilled(headerX, originY, originX, tracksBottom(), EditorTheme.LANE_HEADER.u32)
        EditorFonts.with(EditorFonts.small) {
            drawList.addText(
                headerX + EditorFonts.px(12f),
                originY + (RULER_HEIGHT - ImGui.getFontSize()) / 2f,
                EditorTheme.TEXT_DIM.u32,
                Widgets.clip(session.project.name, HEADER_WIDTH - EditorFonts.px(44f))
            )
        }
        var y = originY + RULER_HEIGHT
        for (lane in lanes) {
            val muted = session.project.lane(lane.kind).muted
            drawList.addRectFilled(
                headerX + EditorFonts.px(4f),
                y + EditorFonts.px(5f),
                headerX + EditorFonts.px(6f),
                y + lane.height - EditorFonts.px(5f),
                if (muted) EditorTheme.TEXT_DIM.u32(0.4f) else lane.strip.u32,
                1f
            )
            drawList.addLine(headerX, y + lane.height, originX + width, y + lane.height, EditorTheme.LANE_LINE.u32, 1f)
            val iconSize = EditorFonts.px(13f)
            Icons.draw(
                drawList,
                LANE_ICONS[lane.kind] ?: Icon.SLIDERS,
                headerX + EditorFonts.px(12f),
                y + (lane.rowHeight - iconSize) / 2f,
                iconSize,
                if (muted) EditorTheme.TEXT_DIM.u32 else lane.strip.u32
            )
            when (lane.kind) {
                LaneKind.PLAYERS -> gameLanes.drawPlayerHeaders(drawList, y, lane.rowHeight, headerX, originX)
                LaneKind.WORLD -> gameLanes.drawWorldHeaders(drawList, y, lane.rowHeight, headerX)
                else -> EditorFonts.with(EditorFonts.smallMedium) {
                    val textColor = if (muted) EditorTheme.TEXT_DIM.u32 else EditorTheme.TEXT.u32
                    drawList.addText(
                        headerX + EditorFonts.px(31f),
                        y + (lane.height - ImGui.getFontSize()) / 2f,
                        textColor,
                        lane.label
                    )
                }
            }
            y += lane.height
        }
        drawList.addLine(originX - 1f, originY, originX - 1f, tracksBottom(), EditorTheme.BORDER.u32, 1f)
    }

    private fun headerWidgets(session: EditorSession) {
        val project = session.project
        for (lane in lanes) {
            val top = laneTop(lane.kind)
            val size = EditorFonts.px(18f)
            val y = top + (lane.height - size) / 2f
            var x = originX - EditorFonts.px(8f) - size
            val reveal = drag == DragKind.NONE && ImGui.isWindowHovered() && ImGui.isMouseHoveringRect(headerX, top, originX, top + lane.height)
            ImGui.pushID("lane-${lane.kind.name}")
            try {
                when (lane.kind) {
                    LaneKind.CAMERA -> {
                        ImGui.setCursorScreenPos(x, y)
                        if (reveal && Widgets.iconButton(
                                "add",
                                Icon.PLUS,
                                size,
                                "Add camera keyframe at playhead (Ctrl+K)",
                                iconScale = 0.55f
                            )
                        ) session.keyframeAtPlayhead(context.host.camera.currentPose())
                        x -= size + 2f
                        ImGui.setCursorScreenPos(x, y)
                        val state = project.lane(LaneKind.CAMERA)
                        if (reveal || state.locked) Widgets.iconToggle(
                            "lock",
                            if (state.locked) Icon.LOCK else Icon.UNLOCK,
                            state.locked,
                            size,
                            if (state.locked) "Unlock camera lane" else "Lock camera lane (prevents dragging)"
                        )?.let { session.execute(SetLaneState(LaneKind.CAMERA, state.copy(locked = it))) }
                        x -= size + 2f
                        ImGui.setCursorScreenPos(x, y)
                        if (reveal || state.muted) Widgets.iconToggle(
                            "eye",
                            if (state.muted) Icon.EYE_OFF else Icon.EYE,
                            !state.muted,
                            size,
                            if (state.muted) "Enable camera path (camera follows keyframes)" else "Disable camera path"
                        )?.let { session.execute(SetLaneState(LaneKind.CAMERA, state.copy(muted = !it))) }
                        x -= 4f
                        EditorFonts.with(EditorFonts.small) {
                            val label = "${keyTimes.size}"
                            val labelWidth = Widgets.textWidth(label)
                            ImGui.getWindowDrawList().addText(
                                x - labelWidth,
                                top + (lane.height - ImGui.getFontSize()) / 2f,
                                EditorTheme.TEXT_DIM.u32,
                                label
                            )
                        }
                    }

                    LaneKind.SPEED, LaneKind.FOV, LaneKind.TIME_OF_DAY, LaneKind.SHAKE, LaneKind.FREEZE, LaneKind.SHAKE_FREQUENCY -> {
                        val valueLane = ValueLane.entries.first { it.kind == lane.kind }
                        ImGui.setCursorScreenPos(x, y)
                        if (reveal && Widgets.iconButton(
                                "add",
                                Icon.PLUS,
                                size,
                                VALUE_ADD_TOOLTIPS[valueLane] ?: "Add keyframe",
                                iconScale = 0.55f
                            )
                        ) addValueKeyframe(session, valueLane, session.playheadNanos)
                        x -= size + 2f
                        ImGui.setCursorScreenPos(x, y)
                        val state = project.lane(lane.kind)
                        if (reveal || state.muted) Widgets.iconToggle(
                            "eye",
                            if (state.muted) Icon.EYE_OFF else Icon.EYE,
                            !state.muted,
                            size,
                            if (state.muted) "Enable ${valueLane.label.lowercase()} lane" else "Disable ${valueLane.label.lowercase()} lane"
                        )?.let { session.execute(SetLaneState(lane.kind, state.copy(muted = !it))) }
                    }

                    LaneKind.VIEW -> {
                        ImGui.setCursorScreenPos(x, y)
                        if (reveal && Widgets.iconButton(
                                "add",
                                Icon.PLUS,
                                size,
                                "Add view keyframe: switches to the current camera mode and target from here on",
                                iconScale = 0.55f
                            )
                        ) addViewKeyframe(session, session.playheadNanos)
                        x -= size + 2f
                        ImGui.setCursorScreenPos(x, y)
                        val state = project.lane(LaneKind.VIEW)
                        if (reveal || state.muted) Widgets.iconToggle(
                            "eye",
                            if (state.muted) Icon.EYE_OFF else Icon.EYE,
                            !state.muted,
                            size,
                            if (state.muted) "Enable view switches" else "Disable view switches"
                        )?.let { session.execute(SetLaneState(LaneKind.VIEW, state.copy(muted = !it))) }
                    }

                    LaneKind.CLIPS -> {
                        ImGui.setCursorScreenPos(x, y)
                        if (reveal && Widgets.iconButton(
                                "add",
                                Icon.PLUS,
                                size,
                                "Create clip from in/out points",
                                iconScale = 0.55f
                            )
                        ) clipFromInOut(session)
                    }

                    LaneKind.MARKERS -> {
                        ImGui.setCursorScreenPos(x, y)
                        if (reveal && Widgets.iconButton(
                                "add",
                                Icon.PLUS,
                                size,
                                "Add marker at playhead (M)",
                                iconScale = 0.55f
                            )
                        ) addMarker(session, session.playheadNanos)
                    }

                    LaneKind.TIMELAPSE -> {
                        ImGui.setCursorScreenPos(x, y)
                        if (reveal && Widgets.iconButton(
                                "add",
                                Icon.PLUS,
                                size,
                                "Add timelapse skip at playhead",
                                iconScale = 0.55f
                            )
                        ) addTimelapse(session, session.playheadNanos)
                        x -= size + 2f
                        ImGui.setCursorScreenPos(x, y)
                        val state = project.lane(LaneKind.TIMELAPSE)
                        if (reveal || state.muted) Widgets.iconToggle(
                            "eye",
                            if (state.muted) Icon.EYE_OFF else Icon.EYE,
                            !state.muted,
                            size,
                            if (state.muted) "Enable timelapse skips" else "Disable timelapse skips"
                        )?.let { session.execute(SetLaneState(LaneKind.TIMELAPSE, state.copy(muted = !it))) }
                    }

                    LaneKind.POSE -> {
                        val entity = context.selectedEntityId?.takeIf { PoseTools.poseable(session, it) }
                        ImGui.setCursorScreenPos(x, y)
                        if (reveal && Widgets.iconButton(
                                "add",
                                Icon.PLUS,
                                size,
                                if (entity == null) "Select a player to key its pose" else "Key the selected entity's pose at the playhead",
                                enabled = entity != null,
                                iconScale = 0.55f
                            ) && entity != null
                        ) PoseTools.keyHere(session, entity)
                        x -= size + 2f
                        ImGui.setCursorScreenPos(x, y)
                        val state = project.lane(LaneKind.POSE)
                        if (reveal || state.muted) Widgets.iconToggle(
                            "eye",
                            if (state.muted) Icon.EYE_OFF else Icon.EYE,
                            !state.muted,
                            size,
                            if (state.muted) "Enable poses" else "Disable poses"
                        )?.let { session.execute(SetLaneState(LaneKind.POSE, state.copy(muted = !it))) }
                    }

                    LaneKind.MOMENTS -> {
                        ImGui.setCursorScreenPos(x, y)
                        if (reveal && Widgets.iconButton(
                                "add",
                                Icon.PLUS,
                                size,
                                "Add a moment at the playhead",
                                iconScale = 0.55f
                            )
                        ) gameLanes.addManual(session, session.playheadNanos)
                        x -= size + 2f
                        EditorFonts.with(EditorFonts.small) {
                            val kept = project.moments.size
                            val detected = gameLanes.moments(session).size - kept
                            val label = if (detected > 0) "$kept + $detected" else "$kept"
                            val labelWidth = Widgets.textWidth(label)
                            ImGui.getWindowDrawList().addText(
                                x - labelWidth,
                                top + (lane.height - ImGui.getFontSize()) / 2f,
                                EditorTheme.TEXT_DIM.u32,
                                label
                            )
                        }
                    }

                    LaneKind.EVENTS -> {
                        val events = session.events
                        EditorFonts.with(EditorFonts.small) {
                            val label =
                                if (events.complete) "${events.events.size}" else "${(events.progress * 100).toInt()}%"
                            val labelWidth = Widgets.textWidth(label)
                            ImGui.getWindowDrawList().addText(
                                originX - 10f - labelWidth,
                                top + (lane.height - ImGui.getFontSize()) / 2f,
                                EditorTheme.TEXT_DIM.u32,
                                label
                            )
                        }
                        if (!events.complete) {
                            val barY = top + lane.height - 4f
                            ImGui.getWindowDrawList()
                                .addRectFilled(headerX + 12f, barY, originX - 12f, barY + 2f, EditorTheme.CONTROL.u32)
                            ImGui.getWindowDrawList().addRectFilled(
                                headerX + 12f,
                                barY,
                                headerX + 12f + (originX - 24f - headerX) * events.progress.toFloat(),
                                barY + 2f,
                                EditorTheme.ACCENT_TEXT.u32
                            )
                        }
                    }

                    else -> Unit
                }
            } finally {
                ImGui.popID()
            }
        }
    }

    private var reorderLane: LaneKind? = null

    private fun headerMenus(session: EditorSession, view: TimelineView) {
        for (lane in lanes) {
            val top = laneTop(lane.kind)
            ImGui.setCursorScreenPos(headerX, top)
            ImGui.pushID("hdr-${lane.kind.name}")
            try {
                ImGui.invisibleButton("hit", HEADER_WIDTH - 72f, lane.height)
                if (ImGui.isItemHovered() && ImGui.isMouseClicked(ImGuiMouseButton.Right)) contextHeader = lane.kind
                if (lane.kind == LaneKind.PLAYERS && ImGui.isItemClicked(ImGuiMouseButton.Left)) gameLanes.togglePlayer(
                    ImGui.getMousePosY() - top,
                    lane.rowHeight
                )
                if (ImGui.isItemActive()) {
                    reorderLane = lane.kind
                    laneAt(ImGui.getMousePosY())?.kind?.let { target ->
                        if (target != lane.kind) reorderLanes(lane.kind, target)
                    }
                }
                if (ImGui.isItemDeactivated()) reorderLane = null
                if (reorderLane == lane.kind) ImGui.getWindowDrawList().addRect(
                    headerX + 1f, top + 1f, originX - 1f, top + lane.height - 1f, EditorTheme.SELECTION.u32, 2f
                )
                if (ImGui.beginPopupContextItem("lane-menu")) {
                    laneMenu(session, view, lane)
                    ImGui.endPopup()
                }
            } finally {
                ImGui.popID()
            }
        }
    }

    private fun reorderLanes(from: LaneKind, to: LaneKind) {
        val order = context.ui.laneOrder.toMutableList()
        val fromIndex = order.indexOf(from)
        val toIndex = order.indexOf(to)
        if (fromIndex < 0 || toIndex < 0) return
        order.removeAt(fromIndex)
        order.add(toIndex, from)
        context.ui.laneOrder = order
    }

    private fun laneMenu(session: EditorSession, view: TimelineView, lane: Lane) {
        val state = session.project.lane(lane.kind)
        Widgets.mutedText("${lane.label} track")
        ImGui.separator()
        when (lane.kind) {
            LaneKind.CAMERA -> {
                if (ImGui.menuItem(
                        "Add keyframe at playhead",
                        "Ctrl+K"
                    )
                ) session.keyframeAtPlayhead(context.host.camera.currentPose())
                if (ImGui.menuItem(if (state.muted) "Enable camera path" else "Disable camera path")) session.execute(
                    SetLaneState(lane.kind, state.copy(muted = !state.muted))
                )
                if (ImGui.menuItem(if (state.locked) "Unlock" else "Lock")) session.execute(
                    SetLaneState(
                        lane.kind,
                        state.copy(locked = !state.locked)
                    )
                )
                if (ImGui.menuItem("Select all keyframes", "", false, keyTimes.isNotEmpty())) session.selection =
                    Selection(keyframeTimes = keyTimes.toSet())
                if (ImGui.beginMenu("Path tools", keyTimes.size >= 2)) {
                    val path = session.project.camera
                    if (ImGui.menuItem("Reverse direction")) session.execute(
                        ReplaceCameraPath(
                            PathTools.reversed(path),
                            "Reverse camera path"
                        )
                    )
                    Widgets.tooltip("Play the same path backwards: the last keyframe becomes the first")
                    if (ImGui.menuItem("Fit to in/out range")) session.execute(
                        ReplaceCameraPath(
                            PathTools.retimed(
                                path,
                                inPoint(session),
                                outPoint(session)
                            ), "Fit camera path to in/out"
                        )
                    )
                    Widgets.tooltip("Stretch or squeeze the keyframes so the path starts at the in point and ends at the out point")
                    if (ImGui.menuItem("Start at playhead")) session.execute(
                        ReplaceCameraPath(
                            PathTools.shifted(
                                path,
                                session.playheadNanos - keyTimes.first()
                            ), "Shift camera path"
                        )
                    )
                    if (ImGui.menuItem("Space keyframes evenly")) session.execute(
                        ReplaceCameraPath(
                            PathTools.evenlySpaced(
                                path
                            ), "Space keyframes evenly"
                        )
                    )
                    if (ImGui.menuItem("Time by distance (constant speed)")) session.execute(
                        ReplaceCameraPath(
                            PathTools.byDistance(
                                path
                            ), "Retime camera path by distance"
                        )
                    )
                    Widgets.tooltip("Re-times keyframes so the camera moves at a constant speed along the path")
                    ImGui.separator()
                    if (ImGui.menuItem("Simplify (remove redundant keyframes)")) session.execute(
                        ReplaceCameraPath(
                            PathTools.simplified(path, 0.35),
                            "Simplify camera path"
                        )
                    )
                    Widgets.tooltip("Drops keyframes that sit within a third of a block of the straight line between their neighbours; great after recording a flight")
                    if (ImGui.menuItem("Mirror east-west")) session.execute(
                        ReplaceCameraPath(
                            PathTools.mirrored(
                                path,
                                0
                            ), "Mirror camera path"
                        )
                    )
                    if (ImGui.menuItem("Mirror north-south")) session.execute(
                        ReplaceCameraPath(
                            PathTools.mirrored(
                                path,
                                2
                            ), "Mirror camera path"
                        )
                    )
                    ImGui.separator()
                    if (ImGui.menuItem("Face direction of travel")) session.execute(
                        ReplaceCameraPath(
                            PathTools.facingTravel(
                                path
                            ), "Face camera path along travel"
                        )
                    )
                    Widgets.tooltip("Points every keyframe toward the next one, like a fly-through")
                    if (ImGui.menuItem("Level the horizon (roll 0)")) session.execute(
                        ReplaceCameraPath(
                            PathTools.levelled(
                                path
                            ), "Level camera path"
                        )
                    )
                    if (ImGui.menuItem("Use current FOV everywhere")) session.execute(
                        ReplaceCameraPath(
                            PathTools.withUniformFov(
                                path,
                                context.host.camera.currentPose().fov
                            ), "Set camera path FOV"
                        )
                    )
                    ImGui.separator()
                    for (mode in SegmentMode.entries) {
                        if (ImGui.menuItem("All ${mode.label.lowercase()}")) session.execute(
                            ReplaceCameraPath(
                                PathTools.withUniformMode(
                                    path,
                                    mode
                                ), "Set all keyframes to ${mode.label.lowercase()}"
                            )
                        )
                    }
                    ImGui.endMenu()
                }
                ImGui.separator()
                if (ImGui.menuItem("Clear camera path", "", false, keyTimes.isNotEmpty())) {
                    session.execute(ClearCameraPath())
                    session.selection = Selection.NONE
                }
            }

            LaneKind.VIEW -> {
                if (ImGui.menuItem("Add view keyframe at playhead")) addViewKeyframe(session, session.playheadNanos)
                if (ImGui.menuItem(if (state.muted) "Enable" else "Disable")) session.execute(
                    SetLaneState(
                        lane.kind,
                        state.copy(muted = !state.muted)
                    )
                )
                ImGui.separator()
                if (viewKeys.isEmpty()) {
                    if (ImGui.menuItem("Hide track")) view.shownLanes.remove(lane.kind)
                } else if (ImGui.menuItem("Clear and hide track")) {
                    session.execute(RemoveViewKeyframes(viewKeys.map { it.timeNanos }.toSet()))
                    session.selection = Selection.NONE
                    view.shownLanes.remove(lane.kind)
                }
            }

            LaneKind.CLIPS -> if (ImGui.menuItem("Clip from in/out")) clipFromInOut(session)
            LaneKind.MARKERS -> if (ImGui.menuItem("Add marker at playhead", "M")) addMarker(
                session,
                session.playheadNanos
            )

            LaneKind.TIMELAPSE -> {
                if (ImGui.menuItem("Add timelapse skip at playhead")) addTimelapse(session, session.playheadNanos)
                if (ImGui.menuItem(if (state.muted) "Enable" else "Disable")) session.execute(
                    SetLaneState(lane.kind, state.copy(muted = !state.muted))
                )
                ImGui.separator()
                if (timelapses.isEmpty()) {
                    if (ImGui.menuItem("Hide track")) view.shownLanes.remove(lane.kind)
                } else if (ImGui.menuItem("Clear and hide track")) {
                    timelapses.forEach { session.execute(RemoveTimelapse(it.id)) }
                    session.selection = Selection.NONE
                    view.shownLanes.remove(lane.kind)
                }
            }

            LaneKind.POSE -> {
                if (ImGui.menuItem(if (state.muted) "Enable" else "Disable")) session.execute(
                    SetLaneState(lane.kind, state.copy(muted = !state.muted))
                )
                ImGui.separator()
                if (poseKeys.isEmpty()) {
                    if (ImGui.menuItem("Hide track")) view.shownLanes.remove(lane.kind)
                } else if (ImGui.menuItem("Clear and hide track")) {
                    for ((id, track) in session.project.poses.toMap()) session.execute(
                        RemovePoseKeyframes(
                            id,
                            track.keyframes.map { it.timeNanos }.toSet()
                        )
                    )
                    context.selectedBodyPart = null
                    view.shownLanes.remove(lane.kind)
                }
            }

            LaneKind.EVENTS -> Widgets.smallText(
                "Recorded events: chat, deaths, damage, explosions",
                EditorTheme.TEXT_DIM.u32
            )

            LaneKind.PLAYERS -> {
                gameLanes.playersLaneMenu()
                ImGui.separator()
                if (ImGui.menuItem("Hide track")) hideGameLane(view, lane.kind)
            }

            LaneKind.WORLD -> {
                gameLanes.worldLaneMenu()
                ImGui.separator()
                if (ImGui.menuItem("Hide track")) hideGameLane(view, lane.kind)
            }

            LaneKind.MOMENTS -> {
                gameLanes.momentsLaneMenu(session)
                ImGui.separator()
                if (ImGui.menuItem("Hide track")) hideGameLane(view, lane.kind)
            }


            else -> {
                val valueLane = ValueLane.entries.firstOrNull { it.kind == lane.kind } ?: return
                val keys = valueKeys[valueLane] ?: emptyList()
                if (ImGui.menuItem("Add keyframe at playhead")) addValueKeyframe(
                    session,
                    valueLane,
                    session.playheadNanos
                )
                if (ImGui.menuItem(if (state.muted) "Enable" else "Disable")) session.execute(
                    SetLaneState(
                        lane.kind,
                        state.copy(muted = !state.muted)
                    )
                )
                if (ImGui.menuItem("Select all keyframes", "", false, keys.isNotEmpty())) session.selection =
                    Selection(valueKeys = keys.map { ValueKey(valueLane, it.timeNanos) }.toSet())
                ImGui.separator()
                if (keys.isEmpty()) {
                    if (ImGui.menuItem("Hide track")) view.shownLanes.remove(lane.kind)
                } else if (ImGui.menuItem("Clear and hide track")) {
                    session.execute(RemoveValueKeyframes(keys.map { ValueKey(valueLane, it.timeNanos) }.toSet()))
                    session.selection = Selection.NONE
                    view.shownLanes.remove(lane.kind)
                }
                VALUE_HINTS[valueLane]?.let {
                    ImGui.separator()
                    Widgets.smallText(it, EditorTheme.TEXT_DIM.u32)
                }
            }
        }
    }

    private fun hideGameLane(view: TimelineView, kind: LaneKind) {
        view.shownLanes.remove(kind)
        view.hiddenLanes.add(kind)
    }

    private fun addTrackButton(view: TimelineView) {
        val hiddenLanes = allLanes.filter { optional(it.kind) && lanes.none { shown -> shown.kind == it.kind } }
        val size = EditorFonts.px(18f)
        ImGui.setCursorScreenPos(originX - EditorFonts.px(8f) - size, originY + (RULER_HEIGHT - size) / 2f)
        if (Widgets.iconButton(
                "add-track",
                Icon.PLUS,
                size,
                if (hiddenLanes.isEmpty()) "All tracks are shown" else "Show a track: speed ramps, FOV, time of day, shake, view switches or freezes",
                enabled = hiddenLanes.isNotEmpty(),
                iconScale = 0.6f
            )
        ) ImGui.openPopup("add-track")
        if (Widgets.beginPopup("add-track")) {
            Widgets.mutedText("Show track")
            ImGui.separator()
            for (lane in hiddenLanes) {
                val hint = ValueLane.entries.firstOrNull { it.kind == lane.kind }?.let { VALUE_HINTS[it] }
                    ?: when (lane.kind) {
                        LaneKind.PLAYERS -> "One row per player with presence, health, kills and deaths."
                        LaneKind.WORLD -> "Block changes, explosions, projectiles, sounds and dimension changes."
                        LaneKind.MOMENTS -> "Detected and kept moments: kills, clutches, fights, escapes."
                        else -> "View keyframes switch between camera modes and targets over time."
                    }
                if (ImGui.menuItem(lane.label)) {
                    view.shownLanes.add(lane.kind)
                    view.hiddenLanes.remove(lane.kind)
                }
                Widgets.tooltip(hint)
            }
            Widgets.endPopup()
        }
    }

    private fun drawLaneBackgrounds(drawList: ImDrawList) {
        val mouseY = ImGui.getMousePosY()
        val mouseX = ImGui.getMousePosX()
        var y = originY + RULER_HEIGHT
        for ((index, lane) in lanes.withIndex()) {
            val base = if (index % 2 == 0) EditorTheme.LANE_A else EditorTheme.LANE_B
            drawList.addRectFilled(originX, y, originX + width, y + lane.height, base.u32)
            if (drag == DragKind.NONE && mouseX >= originX && mouseY >= y && mouseY < y + lane.height && ImGui.isWindowHovered()) {
                drawList.addRectFilled(originX, y, originX + width, y + lane.height, EditorTheme.BORDER_SOFT.u32(0.03f))
            }
            drawList.addLine(originX, y + lane.height, originX + width, y + lane.height, EditorTheme.LANE_LINE.u32, 1f)
            y += lane.height
        }
    }

    private fun inPoint(session: EditorSession): Long = session.project.inPointNanos.coerceIn(0L, duration)

    private fun outPoint(session: EditorSession): Long =
        if (session.project.outPointNanos > 0L) session.project.outPointNanos.coerceIn(0L, duration) else duration

    private fun drawWorkArea(drawList: ImDrawList, session: EditorSession) {
        val left = xAt(if (drag == DragKind.IN_POINT) dragCurrentNanos else inPoint(session))
        val right = xAt(if (drag == DragKind.OUT_POINT) dragCurrentNanos else outPoint(session))
        val bottom = tracksBottom()
        drawList.addRectFilled(
            maxOf(originX, left),
            originY + RULER_HEIGHT,
            minOf(originX + width, right),
            bottom,
            EditorTheme.WORK_AREA.u32
        )
        if (left > originX) drawList.addRectFilled(
            originX,
            originY + RULER_HEIGHT,
            minOf(left, originX + width),
            bottom,
            EditorTheme.APP_BG.u32(0.35f)
        )
        if (right < originX + width) drawList.addRectFilled(
            maxOf(right, originX),
            originY + RULER_HEIGHT,
            originX + width,
            bottom,
            EditorTheme.APP_BG.u32(0.35f)
        )
    }

    private fun drawInOutHandles(drawList: ImDrawList, session: EditorSession) {
        val inNanos = if (drag == DragKind.IN_POINT) dragCurrentNanos else inPoint(session)
        val outNanos = if (drag == DragKind.OUT_POINT) dragCurrentNanos else outPoint(session)
        val bottom = originY + RULER_HEIGHT + tracksHeight
        val trimmed = inNanos > 0L || outNanos < duration
        val color = if (trimmed) EditorTheme.SELECTION else EditorTheme.IN_OUT
        val top = originY + RULER_HEIGHT - HANDLE_HEIGHT
        val left = xAt(inNanos)
        val right = xAt(outNanos)
        if (trimmed) {
            val x1 = left.coerceIn(originX, originX + width)
            val x2 = right.coerceIn(originX, originX + width)
            if (x2 > x1) drawList.addRect(x1, top, x2, bottom, color.u32(0.5f), 0f, 0, 1f)
        }
        if (left >= originX - 1f && left <= originX + width + 1f) {
            drawList.addLine(left, top, left, bottom, color.u32(0.7f), 1f)
            drawList.addRectFilled(left, top, left + HANDLE_WIDTH, originY + RULER_HEIGHT, color.u32, 2f)
            drawList.addRectFilled(left + EditorFonts.px(3f), top + EditorFonts.px(3f), left + HANDLE_WIDTH - EditorFonts.px(3f), originY + RULER_HEIGHT - EditorFonts.px(3f), EditorTheme.PANEL_SUNKEN.u32(0.6f), 1f)
        }
        if (right >= originX - 1f && right <= originX + width + 1f) {
            drawList.addLine(right, top, right, bottom, color.u32(0.7f), 1f)
            drawList.addRectFilled(right - HANDLE_WIDTH, top, right, originY + RULER_HEIGHT, color.u32, 2f)
            drawList.addRectFilled(right - HANDLE_WIDTH + EditorFonts.px(3f), top + EditorFonts.px(3f), right - EditorFonts.px(3f), originY + RULER_HEIGHT - EditorFonts.px(3f), EditorTheme.PANEL_SUNKEN.u32(0.6f), 1f)
        }
    }

    private fun drawRuler(drawList: ImDrawList, view: TimelineView) {
        drawList.addRectFilled(originX, originY, originX + width, originY + RULER_HEIGHT, EditorTheme.RULER_BG.u32)
        drawSegments(drawList)
        val step = rulerStep(view)
        val minor = step / MINOR_DIVISIONS
        var tick = (view.offsetNanos / minor) * minor
        val end = view.offsetNanos + visibleNanos
        val labelFont = EditorFonts.small
        val fontSize = 13
        while (tick <= end) {
            val x = xAt(tick)
            if (x >= originX && x <= originX + width) {
                val major = tick % step == 0L
                val height = if (major) 9f else 4f
                drawList.addLine(
                    x,
                    originY + RULER_HEIGHT - height,
                    x,
                    originY + RULER_HEIGHT,
                    if (major) EditorTheme.TEXT_MUTED.u32 else EditorTheme.TEXT_DIM.u32(0.6f),
                    1f
                )
                if (major) drawList.addText(
                    labelFont,
                    fontSize,
                    x + 4f,
                    originY + 3f,
                    EditorTheme.TEXT_MUTED.u32,
                    rulerLabel(tick, step)
                )
                if (major) drawList.addLine(
                    x,
                    originY + RULER_HEIGHT,
                    x,
                    tracksBottom(),
                    EditorTheme.LANE_LINE.u32(0.18f),
                    1f
                )
            }
            tick += minor
        }
        drawList.addLine(
            originX,
            originY + RULER_HEIGHT,
            originX + width,
            originY + RULER_HEIGHT,
            EditorTheme.BORDER.u32,
            1f
        )
    }

    private fun drawSegments(drawList: ImDrawList) {
        val project = context.session?.project ?: return
        if (!project.isSequence) return
        val spans = project.segmentSpans()
        val bandTop = tracksBottom() - EditorFonts.px(4f)
        for ((index, span) in spans.withIndex()) {
            val x1 = xAt(span.first).coerceIn(originX, originX + width)
            val x2 = xAt(span.second).coerceIn(originX, originX + width)
            if (x2 <= x1) continue
            val color = SEGMENT_COLORS[index % SEGMENT_COLORS.size]
            drawList.addRectFilled(x1, originY + RULER_HEIGHT, x2, tracksBottom(), color.u32(0.05f))
            drawList.addRectFilled(x1, bandTop, x2, tracksBottom(), color.u32(0.7f))
            drawList.addLine(x1, originY, x1, tracksBottom(), color.u32(0.5f), 1f)
            val label = "${index + 1}  " + project.segments[index].gameplay.fileName.toString().substringBeforeLast('.')
            EditorFonts.with(EditorFonts.small) {
                if (Widgets.textWidth(label) + 12f < x2 - x1) drawList.addText(
                    x1 + 6f,
                    bandTop - ImGui.getFontSize() - 2f,
                    color.u32(0.9f),
                    label
                )
            }
        }
    }

    private fun rulerLabel(nanos: Long, step: Long): String {
        val clock = TimeFormat.clock(nanos)
        return if (step < Nanos.PER_SECOND) clock else clock.substringBefore('.')
    }

    private fun drawCameraLane(drawList: ImDrawList, session: EditorSession, nowNanos: Long) {
        val top = laneTop(LaneKind.CAMERA)
        val centerY = top + laneHeight(LaneKind.CAMERA) / 2f
        val selection = session.selection.keyframeTimes
        val muted = session.project.lane(LaneKind.CAMERA).muted
        val alpha = if (muted) 0.4f else 1f
        for (index in 0 until keyframes.size - 1) {
            val from = keyframes[index]
            val to = keyframes[index + 1]
            val x1 = xAt(displayTime(from.timeNanos, selection))
            val x2 = xAt(displayTime(to.timeNanos, selection))
            if (x2 < originX || x1 > originX + width) continue
            val color = modeColor(from.mode).u32(0.7f * alpha)
            if (from.mode == SegmentMode.HOLD || from.easing.isLinear || x2 - x1 < EASE_GLYPH_MIN_WIDTH) {
                drawList.addLine(x1, centerY, x2, centerY, color, 2f)
            } else {
                val rise = laneHeight(LaneKind.CAMERA) * 0.28f
                val steps = minOf(48, ((x2 - x1) / 4f).toInt().coerceAtLeast(8))
                var previousX = x1
                var previousY = centerY + rise
                for (step in 1..steps) {
                    val t = step.toDouble() / steps
                    val x = x1 + (x2 - x1) * t.toFloat()
                    val y = centerY + rise - rise * 2f * from.easing.clamped(t).toFloat()
                    drawList.addLine(previousX, previousY, x, y, color, 2f)
                    previousX = x
                    previousY = y
                }
            }
        }
        val playhead = session.playheadNanos
        val pulse = (0.65f + 0.35f * Math.sin(nowNanos / 1.6e8).toFloat())
        for (frame in keyframes) {
            val selected = frame.timeNanos in selection
            val x = xAt(displayTime(frame.timeNanos, selection))
            if (x < originX - KEY_RADIUS || x > originX + width + KEY_RADIUS) continue
            val radius = if (selected) KEY_RADIUS + 1.5f else KEY_RADIUS
            val atPlayhead = Math.abs(frame.timeNanos - playhead) < Nanos.PER_MILLI * 5
            val fill = modeColor(frame.mode).u32(if (atPlayhead) pulse * alpha else alpha)
            Icons.diamond(drawList, x, centerY, radius, fill, true)
            Icons.diamond(
                drawList,
                x,
                centerY,
                radius,
                if (selected) EditorTheme.SELECTION.u32 else EditorTheme.APP_BG.u32(0.8f),
                false,
                if (selected) 2f else 1f
            )
            if (dragDuplicate && drag == DragKind.KEYFRAMES && frame.timeNanos in dragTimes) {
                Icons.diamond(
                    drawList,
                    xAt(frame.timeNanos),
                    centerY,
                    KEY_RADIUS,
                    modeColor(frame.mode).u32(0.35f),
                    true
                )
            }
        }
    }

    private fun displayTime(nanos: Long, selection: Set<Long>): Long =
        if (drag == DragKind.KEYFRAMES && !dragDuplicate && nanos in dragTimes) maxOf(
            0L,
            nanos + dragDelta
        ) else if (drag == DragKind.KEYFRAMES && dragDuplicate && nanos in dragTimes) maxOf(
            0L,
            nanos + dragDelta
        ) else nanos

    private fun drawValueLane(drawList: ImDrawList, session: EditorSession, lane: ValueLane) {
        val top = laneTop(lane.kind)
        val height = laneHeight(lane.kind)
        val muted = session.project.lane(lane.kind).muted
        val alpha = if (muted) 0.4f else 1f
        val track = session.project.valueTrack(lane)
        val keys = valueKeys[lane] ?: emptyList()
        val selection = session.selection.valueTimes(lane)
        val color = laneColor(lane.kind)
        if (keys.size >= 2) {
            val first = keys.first().timeNanos
            val last = keys.last().timeNanos
            val startX = maxOf(originX, xAt(first))
            val endX = minOf(originX + width, xAt(last))
            var x = startX
            var previousY = Float.NaN
            while (x <= endX) {
                val value = track.valueAt(nanosAt(x)) ?: lane.default
                val y = valueY(lane, top, height, value)
                if (!previousY.isNaN()) drawList.addLine(x - CURVE_STEP, previousY, x, y, color.u32(0.8f * alpha), 1.5f)
                previousY = y
                x += CURVE_STEP
            }
        }
        for (frame in keys) {
            val time =
                if (drag == DragKind.VALUE_KEY && dragLane == lane && dragId == frame.timeNanos) dragCurrentNanos else frame.timeNanos
            val x = xAt(time)
            if (x < originX - 8f || x > originX + width + 8f) continue
            val y = valueY(lane, top, height, frame.value)
            val selected = frame.timeNanos in selection
            drawList.addCircleFilled(x, y, 5f, color.u32(alpha), 12)
            drawList.addCircle(
                x,
                y,
                5f,
                if (selected) EditorTheme.SELECTION.u32 else EditorTheme.APP_BG.u32(0.8f),
                12,
                if (selected) 2f else 1f
            )
            EditorFonts.with(EditorFonts.small) {
                drawList.addText(x + 8f, top + 2f, EditorTheme.TEXT_MUTED.u32(alpha), lane.format(frame.value))
            }
        }
    }

    private fun valueY(lane: ValueLane, top: Float, height: Float, value: Double): Float {
        val normalized = if (lane == ValueLane.SPEED) {
            ((ln(value.coerceIn(0.1, 8.0)) / Math.log(8.0)) + 1.0) / 2.0
        } else {
            ((value - lane.min) / (lane.max - lane.min)).coerceIn(0.0, 1.0)
        }
        return top + height - 5f - (height - 10f) * normalized.toFloat()
    }

    private fun drawViewLane(drawList: ImDrawList, session: EditorSession) {
        val top = laneTop(LaneKind.VIEW)
        val height = laneHeight(LaneKind.VIEW)
        val muted = session.project.lane(LaneKind.VIEW).muted
        val alpha = if (muted) 0.4f else 1f
        val selection = session.selection.viewTimes
        for ((index, frame) in viewKeys.withIndex()) {
            val time = if (drag == DragKind.VIEW_KEY && dragId == frame.timeNanos) dragCurrentNanos else frame.timeNanos
            val next = viewKeys.getOrNull(index + 1)?.timeNanos ?: duration
            val left = xAt(time)
            val right = minOf(xAt(next), originX + width)
            if (right < originX || left > originX + width) continue
            val selected = frame.timeNanos in selection
            val y1 = top + 4f
            val y2 = top + height - 4f
            drawList.addRectFilled(
                left,
                y1,
                maxOf(right, left + 3f),
                y2,
                EditorTheme.CONTROL_ACTIVE.u32(0.55f * alpha),
                3f
            )
            drawList.addRectFilled(left, y1, left + 3f, y2, EditorTheme.ACCENT_TEXT.u32(alpha), 2f)
            if (selected) drawList.addRect(
                left,
                y1,
                maxOf(right, left + 3f),
                y2,
                EditorTheme.SELECTION.u32,
                3f,
                0,
                1.5f
            )
            if (right - left > 30f) {
                drawList.pushClipRect(left + 5f, y1, right - 2f, y2, true)
                EditorFonts.with(EditorFonts.small) {
                    drawList.addText(
                        left + 7f,
                        y1 + (y2 - y1 - ImGui.getFontSize()) / 2f,
                        EditorTheme.TEXT.u32(alpha),
                        viewLabel(frame.value)
                    )
                }
                drawList.popClipRect()
            }
        }
    }

    private fun viewLabel(view: ViewState): String {
        val target =
            if (view.targetEntityId == CameraSettings.TARGET_RECORDER) context.replay?.shadow?.localPlayer?.name
                ?: "recorder" else entityName(view.targetEntityId)
        return if (view.mode == CameraMode.FREE) "Free camera" else "${view.mode.label}   $target"
    }

    private fun entityName(id: Int): String {
        val shadow = context.replay?.shadow ?: return "#$id"
        val entity = shadow.entities[id] ?: return "#$id"
        return entity.uuid?.let { shadow.players.profile(it)?.name } ?: "#$id"
    }

    private fun laneColor(kind: LaneKind): EditorTheme.Rgb =
        allLanes.firstOrNull { it.kind == kind }?.strip ?: EditorTheme.ACCENT_TEXT

    private fun drawClipLane(drawList: ImDrawList, session: EditorSession) {
        val top = laneTop(LaneKind.CLIPS)
        val height = laneHeight(LaneKind.CLIPS)
        val selection = session.selection.clipIds
        val hoveredClip = if (drag == DragKind.NONE) clipAt(ImGui.getMousePosX(), ImGui.getMousePosY()) else null
        for (clip in clips) {
            val dragging =
                dragId == clip.id && (drag == DragKind.CLIP_BODY || drag == DragKind.CLIP_START || drag == DragKind.CLIP_END)
            val start = if (dragging) dragClipStart else clip.startNanos
            val end = if (dragging) dragClipEnd else clip.endNanos
            val left = xAt(start)
            val right = xAt(end)
            if (right < originX || left > originX + width) continue
            val selected = clip.id in selection
            val fill = when {
                selected -> EditorTheme.CLIP_SELECTED
                hoveredClip?.id == clip.id -> EditorTheme.CLIP_HOVER
                else -> EditorTheme.CLIP
            }
            val y1 = top + 3f
            val y2 = top + height - 3f
            drawList.addRectFilled(left, y1, maxOf(right, left + 2f), y2, fill.u32, 3f)
            drawList.addRectFilled(left, y1, maxOf(right, left + 2f), y1 + 3f, EditorTheme.TEXT.u32(0.14f), 3f)
            drawList.addRect(
                left,
                y1,
                maxOf(right, left + 2f),
                y2,
                if (selected) EditorTheme.SELECTION.u32 else EditorTheme.APP_BG.u32(0.7f),
                3f,
                0,
                if (selected) 1.5f else 1f
            )
            if (right - left > 24f) {
                drawList.pushClipRect(maxOf(left + 2f, originX), y1, minOf(right - 2f, originX + width), y2, true)
                EditorFonts.with(EditorFonts.small) {
                    drawList.addText(
                        left + 6f,
                        y1 + (y2 - y1 - ImGui.getFontSize()) / 2f,
                        0xFFFFFFFF.toInt(),
                        clip.title
                    )
                }
                drawList.popClipRect()
            }
        }
    }

    private fun drawMarkerLane(drawList: ImDrawList, session: EditorSession) {
        val top = laneTop(LaneKind.MARKERS)
        val height = laneHeight(LaneKind.MARKERS)
        val selection = session.selection.markerIds
        for (marker in markers) {
            val time = if (drag == DragKind.MARKER && dragId == marker.id) dragCurrentNanos else marker.nanos
            val x = xAt(time)
            if (x < originX - 10f || x > originX + width + 10f) continue
            val selected = marker.id in selection
            val color = Widgets.rgbToU32(marker.color)
            drawList.addRectFilled(x - 1f, top + 4f, x + 1f, top + height - 4f, color)
            if (marker.kind == MarkerKind.NOTE) drawList.addTriangleFilled(
                x - 1f,
                top + 4f,
                x + 9f,
                top + 8f,
                x - 1f,
                top + 12f,
                color
            )
            else Icons.draw(
                drawList,
                MARKER_ICONS[marker.kind] ?: Icon.MARKER,
                x + 1f,
                top + 3f,
                EditorFonts.px(10f),
                color
            )
            if (selected) drawList.addRect(x - 4f, top + 2f, x + 11f, top + height - 2f, EditorTheme.SELECTION.u32, 2f)
            EditorFonts.with(EditorFonts.small) {
                drawList.addText(
                    x + 12f,
                    top + (height - ImGui.getFontSize()) / 2f,
                    EditorTheme.TEXT_MUTED.u32,
                    marker.label
                )
            }
        }
    }

    private fun drawTimelapseLane(drawList: ImDrawList, session: EditorSession) {
        val top = laneTop(LaneKind.TIMELAPSE)
        val height = laneHeight(LaneKind.TIMELAPSE)
        val selection = session.selection.timelapseIds
        val color = EditorTheme.WARNING.u32
        for (mark in timelapses) {
            val time = if (drag == DragKind.TIMELAPSE && dragId == mark.id) dragCurrentNanos else mark.nanos
            val x = xAt(time)
            if (x < originX - 10f || x > originX + width + 10f) continue
            val selected = mark.id in selection
            val midY = top + height / 2f
            drawList.addTriangleFilled(x - 2f, midY - 5f, x + 4f, midY, x - 2f, midY + 5f, color)
            drawList.addTriangleFilled(x + 3f, midY - 5f, x + 9f, midY, x + 3f, midY + 5f, color)
            if (selected) drawList.addRect(x - 4f, top + 2f, x + 13f, top + height - 2f, EditorTheme.SELECTION.u32, 2f)
            EditorFonts.with(EditorFonts.small) {
                drawList.addText(
                    x + 14f,
                    top + (height - ImGui.getFontSize()) / 2f,
                    EditorTheme.TEXT_MUTED.u32,
                    String.format("+%.1fs", mark.skipNanos / Nanos.PER_SECOND.toDouble())
                )
            }
        }
    }

    private fun drawPoseLane(drawList: ImDrawList, session: EditorSession) {
        if (poseKeys.isEmpty() && LaneKind.POSE !in context.timeline.shownLanes) return
        val top = laneTop(LaneKind.POSE)
        val height = laneHeight(LaneKind.POSE)
        if (height <= 0f) return
        val selectedEntity = context.selectedEntityId
        val midY = top + height / 2f
        val half = 5f
        for (key in poseKeys) {
            val x = xAt(key.timeNanos)
            if (x < originX - 10f || x > originX + width + 10f) continue
            val own = selectedEntity == null || key.entityId == selectedEntity
            val color = poseKeyColor(session, key.entityId).u32(if (own) 1f else 0.35f)
            if (key.parts == 0) {
                drawList.addQuad(x, midY - half, x + half, midY, x, midY + half, x - half, midY, color, 1.5f)
            } else {
                drawList.addQuadFilled(x, midY - half, x + half, midY, x, midY + half, x - half, midY, color)
            }
            if (own && selectedEntity != null && Math.abs(session.playheadNanos - key.timeNanos) < Nanos.PER_MILLI) drawList.addQuad(
                x,
                midY - half - 3f,
                x + half + 3f,
                midY,
                x,
                midY + half + 3f,
                x - half - 3f,
                midY,
                EditorTheme.SELECTION.u32,
                1.5f
            )
        }
    }

    private fun poseKeyColor(session: EditorSession, entityId: Int): EditorTheme.Rgb {
        val index = session.events.index ?: return EditorTheme.PURPLE
        val name = index.tracksOf(entityId).firstOrNull()?.name ?: return EditorTheme.PURPLE
        val recorder = session.replay?.shadow?.localPlayer?.entityId == entityId
        return PlayerColors.of(index, name, recorder)
    }

    private fun poseKeyAt(mouseX: Float): PoseKey? {
        val selected = context.selectedEntityId
        val candidates =
            if (selected != null && poseKeys.any { it.entityId == selected }) poseKeys.filter { it.entityId == selected } else poseKeys
        return candidates.minByOrNull { Math.abs(xAt(it.timeNanos) - mouseX) }
            ?.takeIf { Math.abs(xAt(it.timeNanos) - mouseX) <= 8f }
    }

    private fun poseEntityName(session: EditorSession, entityId: Int): String {
        val shadow = session.replay?.shadow
        if (shadow != null && shadow.localPlayer.entityId == entityId) return shadow.localPlayer.name ?: "Recorder"
        session.events.index?.tracksOf(entityId)?.firstOrNull()?.name?.let { return it }
        return shadow?.entities?.get(entityId)?.uuid?.let { shadow.players.profile(it)?.name } ?: "#$entityId"
    }

    private fun selectPoseEntity(session: EditorSession, entityId: Int) {
        val shadow = session.replay?.shadow
        val recorder = shadow?.localPlayer?.entityId == entityId
        val entity = shadow?.entities?.get(entityId)
        context.selectEntity(
            entityId,
            poseEntityName(session, entityId),
            recorder || entity?.isPlayer == true,
            recorder,
            entity?.uuid?.toString()
        )
    }

    private fun poseMenu(session: EditorSession, replay: ReplaySession, key: PoseKey) {
        Widgets.smallText(
            "Pose ${
                poseEntityName(
                    session,
                    key.entityId
                )
            }  ${TimeFormat.clock(key.timeNanos)}  ${if (key.parts == 0) "release" else "${key.parts} limbs"}",
            EditorTheme.TEXT_DIM.u32
        )
        ImGui.separator()
        if (ImGui.menuItem("Go to keyframe")) {
            replay.seek(key.timeNanos)
            selectPoseEntity(session, key.entityId)
        }
        if (ImGui.menuItem("Move to playhead") && session.project.poses[key.entityId]?.at(session.playheadNanos) == null) session.execute(
            MovePoseKeyframe(key.entityId, key.timeNanos, session.playheadNanos)
        )
        ImGui.separator()
        if (ImGui.menuItem("Delete pose keyframe", "Del")) session.execute(
            RemovePoseKeyframes(
                key.entityId,
                setOf(key.timeNanos)
            )
        )
        if (ImGui.menuItem("Clear all poses of ${poseEntityName(session, key.entityId)}")) {
            val track = session.project.poses[key.entityId]
            if (track != null) session.execute(
                RemovePoseKeyframes(
                    key.entityId,
                    track.keyframes.map { it.timeNanos }.toSet()
                )
            )
        }
    }

    private fun drawEventLane(drawList: ImDrawList, session: EditorSession) {
        val top = laneTop(LaneKind.EVENTS)
        val height = laneHeight(LaneKind.EVENTS)
        val centerY = top + height / 2f
        val events = session.events.events
        if (events.isEmpty()) return
        val start = lowerBound(events, context.timeline.offsetNanos - Nanos.PER_SECOND)
        val end = context.timeline.offsetNanos + visibleNanos + Nanos.PER_SECOND
        var index = start
        while (index < events.size) {
            val event = events[index]
            if (event.nanos > end) break
            val x = xAt(event.nanos)
            if (x >= originX - 4f && x <= originX + width + 4f) {
                drawList.addCircleFilled(x, centerY, 3.5f, Widgets.rgbToU32(event.kind.color), 10)
            }
            index++
        }
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

    private fun drawPlayhead(drawList: ImDrawList, positionNanos: Long) {
        val x = xAt(positionNanos)
        if (x < originX || x > originX + width) return
        val bottom = tracksBottom()
        drawList.addLine(x, originY + 4f, x, bottom, EditorTheme.PLAYHEAD.u32, 1.5f)
        val half = EditorFonts.px(5f)
        val headBottom = originY + EditorFonts.px(9f)
        drawList.addRectFilled(x - half, originY + 1f, x + half, headBottom, EditorTheme.PLAYHEAD.u32, 2f)
        drawList.addTriangleFilled(x - half, headBottom - 1f, x + half, headBottom - 1f, x, headBottom + half, EditorTheme.PLAYHEAD.u32)
        if (drag != DragKind.SCRUB) return
        val label = TimeFormat.timecode(positionNanos, context.timeline.renderFps)
        EditorFonts.with(EditorFonts.small) {
            val labelWidth = Widgets.textWidth(label)
            val labelX = if (x + 12f + labelWidth + 8f > originX + width) x - 12f - labelWidth - 8f else x + 10f
            drawList.addRectFilled(
                labelX,
                originY + 3f,
                labelX + labelWidth + 8f,
                originY + 3f + ImGui.getFontSize() + 4f,
                EditorTheme.PLAYHEAD.u32(0.9f),
                3f
            )
            drawList.addText(labelX + 4f, originY + 5f, 0xFF101010.toInt(), label)
        }
    }

    private fun drawHoverLine(drawList: ImDrawList) {
        val mouseX = ImGui.getMousePosX()
        val mouseY = ImGui.getMousePosY()
        if (mouseX < originX || mouseX > originX + width || mouseY > tracksBottom()) return
        drawList.addLine(mouseX, originY + RULER_HEIGHT, mouseX, tracksBottom(), EditorTheme.SKIMMER.u32(0.45f), 1f)
        val label = TimeFormat.clock(nanosAt(mouseX).coerceIn(0L, duration))
        EditorFonts.with(EditorFonts.small) {
            val labelWidth = Widgets.textWidth(label)
            val labelX = if (mouseX + 8f + labelWidth > originX + width) mouseX - 8f - labelWidth else mouseX + 6f
            drawList.addRectFilled(
                labelX - 2f,
                originY + RULER_HEIGHT - 16f,
                labelX + labelWidth + 2f,
                originY + RULER_HEIGHT - 1f,
                EditorTheme.APP_BG.u32(0.85f),
                2f
            )
            drawList.addText(labelX, originY + RULER_HEIGHT - 15f, EditorTheme.TEXT_MUTED.u32, label)
        }
    }

    private fun drawBox(drawList: ImDrawList) {
        val x1 = minOf(dragStartMouseX, ImGui.getMousePosX())
        val x2 = maxOf(dragStartMouseX, ImGui.getMousePosX())
        val y1 = minOf(dragStartMouseY, ImGui.getMousePosY())
        val y2 = maxOf(dragStartMouseY, ImGui.getMousePosY())
        drawList.addRectFilled(x1, y1, x2, y2, EditorTheme.TEXT.u32(0.08f))
        drawList.addRect(x1, y1, x2, y2, EditorTheme.TEXT.u32(0.6f), 0f, 0, 1f)
    }

    private fun navTop(): Float = originY + canvasHeight - NAV_HEIGHT

    private fun tracksBottom(): Float = navTop() - NAV_GAP

    private fun drawNavigator(drawList: ImDrawList, view: TimelineView) {
        val top = navTop()
        drawList.addRectFilled(originX, top, originX + width, top + NAV_HEIGHT, EditorTheme.PANEL_SUNKEN.u32, NAV_HEIGHT / 2f)
        for (time in keyTimes) drawList.addRectFilled(
            navX(time) - 1f,
            top + 3f,
            navX(time) + 1f,
            top + NAV_HEIGHT - 3f,
            EditorTheme.KEYFRAME_SMOOTH.u32(0.35f)
        )
        for (marker in markers) drawList.addRectFilled(
            navX(marker.nanos) - 1f,
            top + 3f,
            navX(marker.nanos) + 1f,
            top + NAV_HEIGHT - 3f,
            Widgets.rgbToU32(marker.color, 0.5f)
        )
        val left = navX(view.offsetNanos)
        val right = navX(view.offsetNanos + visibleNanos)
        val thumbHovered = drag == DragKind.NONE && ImGui.isWindowHovered() && ImGui.isMouseHoveringRect(originX, top, originX + width, top + NAV_HEIGHT)
        drawList.addRectFilled(
            left,
            top + 1f,
            maxOf(right, left + NAV_HEIGHT),
            top + NAV_HEIGHT - 1f,
            EditorTheme.TEXT.u32(if (thumbHovered) 0.3f else 0.2f),
            NAV_HEIGHT / 2f
        )
        val playX = navX(context.replay?.positionNanos ?: 0L)
        drawList.addRectFilled(playX - 1f, top + 1f, playX + 1f, top + NAV_HEIGHT - 1f, EditorTheme.PLAYHEAD.u32, 1f)
    }

    private fun navX(nanos: Long): Float = originX + (nanos.toDouble() / duration * width).toFloat()

    private fun navNanos(x: Float): Long = ((x - originX) / width * duration).toLong()

    private fun hoverFeedback(session: EditorSession) {
        val mouseX = ImGui.getMousePosX()
        val mouseY = ImGui.getMousePosY()
        if (mouseX < originX) return
        if (mouseY >= navTop()) {
            ImGui.setMouseCursor(ImGuiMouseCursor.ResizeEW)
            return
        }
        if (mouseY < originY + RULER_HEIGHT) {
            val handle = handleAt(session, mouseX, mouseY)
            ImGui.setMouseCursor(ImGuiMouseCursor.ResizeEW)
            if (handle != null) ImGui.setTooltip(
                if (handle == DragKind.IN_POINT) "In point ${
                    TimeFormat.clock(
                        inPoint(
                            session
                        )
                    )
                }\nDrag to move" else "Out point ${TimeFormat.clock(outPoint(session))}\nDrag to move"
            )
            return
        }
        val lane = laneAt(mouseY) ?: return
        when (lane.kind) {
            LaneKind.CAMERA -> nearestKeyframe(mouseX)?.let { frame ->
                ImGui.setMouseCursor(ImGuiMouseCursor.Hand)
                ImGui.setTooltip(
                    "Keyframe  ${TimeFormat.clock(frame.timeNanos)}\n${frame.mode.label}   ${frame.easing.label}   FOV %.0f\n%.1f  %.1f  %.1f   yaw %.1f  pitch %.1f\nDrag to move   Alt+drag to duplicate   Double-click to jump".format(
                        frame.pose.fov,
                        frame.pose.position.x,
                        frame.pose.position.y,
                        frame.pose.position.z,
                        frame.pose.rotation.yaw,
                        frame.pose.rotation.pitch
                    ),
                )
            }

            LaneKind.SPEED, LaneKind.FOV, LaneKind.TIME_OF_DAY, LaneKind.SHAKE, LaneKind.FREEZE, LaneKind.SHAKE_FREQUENCY -> {
                val valueLane = ValueLane.entries.first { it.kind == lane.kind }
                nearestValueKey(valueLane, mouseX)?.let { frame ->
                    ImGui.setMouseCursor(ImGuiMouseCursor.Hand)
                    ImGui.setTooltip("${valueLane.label} ${valueLane.format(frame.value)} at ${TimeFormat.clock(frame.timeNanos)}\nDrag to move  -  right-click to change the value")
                }
            }

            LaneKind.VIEW -> nearestViewKey(mouseX)?.let { frame ->
                ImGui.setMouseCursor(ImGuiMouseCursor.Hand)
                ImGui.setTooltip("${viewLabel(frame.value)} from ${TimeFormat.clock(frame.timeNanos)}\nDrag to move  -  right-click for actions")
            }

            LaneKind.CLIPS -> clipAt(mouseX, mouseY)?.let { clip ->
                val left = xAt(clip.startNanos)
                val right = xAt(clip.endNanos)
                val onEdge = mouseX - left <= EDGE_GRAB || right - mouseX <= EDGE_GRAB
                ImGui.setMouseCursor(if (onEdge) ImGuiMouseCursor.ResizeEW else ImGuiMouseCursor.Hand)
                ImGui.setTooltip(
                    "${clip.title}\n${TimeFormat.clock(clip.startNanos)} - ${TimeFormat.clock(clip.endNanos)}  (${
                        TimeFormat.clock(
                            clip.durationNanos
                        )
                    })\n${if (onEdge) "Drag to trim" else "Drag to move   Double-click to play"}"
                )
            }

            LaneKind.MARKERS -> markerAt(mouseX)?.let { marker ->
                ImGui.setMouseCursor(ImGuiMouseCursor.Hand)
                ImGui.setTooltip("${marker.label}\n${TimeFormat.clock(marker.nanos)}\nDrag to move   Right-click to rename")
            }

            LaneKind.TIMELAPSE -> timelapseAt(mouseX)?.let { mark ->
                ImGui.setMouseCursor(ImGuiMouseCursor.Hand)
                ImGui.setTooltip(
                    "Timelapse: skip ${String.format("%.1fs", mark.skipNanos / Nanos.PER_SECOND.toDouble())}\n${
                        TimeFormat.clock(mark.nanos)
                    }\nDrag to move   Right-click to edit"
                )
            }

            LaneKind.POSE -> poseKeyAt(mouseX)?.let { key ->
                ImGui.setMouseCursor(ImGuiMouseCursor.Hand)
                val limbs = if (key.parts == 0) "release" else "${key.parts} limbs"
                ImGui.setTooltip(
                    "Pose: ${
                        poseEntityName(
                            session,
                            key.entityId
                        )
                    }  $limbs\n${TimeFormat.clock(key.timeNanos)}\nClick to go there   Right-click for actions"
                )
            }

            LaneKind.EVENTS -> eventAt(session, mouseX)?.let { event ->
                ImGui.setMouseCursor(ImGuiMouseCursor.Hand)
                ImGui.setTooltip("${event.kind.label}: ${event.label}\n${TimeFormat.clock(event.nanos)}\nClick to jump")
            }

            LaneKind.PLAYERS -> gameLanes.hoverPlayers(session, mouseX, mouseY - laneTop(lane.kind), lane.rowHeight)
            LaneKind.WORLD -> gameLanes.hoverWorld(mouseX, mouseY - laneTop(lane.kind), lane.rowHeight)
            LaneKind.MOMENTS -> gameLanes.momentAt(session, mouseX)?.let { moment ->
                gameLanes.hoverMoment(moment, session.project.moment(moment.id) != null)
            }

            else -> Unit
        }
    }

    private fun handleAt(session: EditorSession, mouseX: Float, mouseY: Float): DragKind? {
        if (mouseY < originY + RULER_HEIGHT - HANDLE_HEIGHT - 2f) return null
        val inX = xAt(inPoint(session))
        val outX = xAt(outPoint(session))
        if (mouseX >= inX - 2f && mouseX <= inX + HANDLE_WIDTH + 2f) return DragKind.IN_POINT
        if (mouseX >= outX - HANDLE_WIDTH - 2f && mouseX <= outX + 2f) return DragKind.OUT_POINT
        return null
    }

    private fun handleInput(session: EditorSession, view: TimelineView, hovered: Boolean, nowNanos: Long) {
        val io = ImGui.getIO()
        val mouseX = ImGui.getMousePosX()
        val mouseY = ImGui.getMousePosY()
        val wheel = io.mouseWheel
        if (hovered && wheel != 0f) {
            if (io.keyShift) view.offsetNanos -= (wheel * visibleNanos * 0.1).toLong() else zoomAround(
                view,
                Math.pow(1.25, wheel.toDouble()),
                nanosAt(mouseX)
            )
            view.offsetNanos = view.offsetNanos.coerceIn(0L, maxOf(0L, duration - visibleNanos))
        }
        if (hovered && ImGui.isMouseClicked(ImGuiMouseButton.Middle)) {
            drag = DragKind.PAN
            dragStartMouseX = mouseX
            dragOriginNanos = view.offsetNanos
        }
        if (hovered && ImGui.isMouseDoubleClicked(ImGuiMouseButton.Left) && mouseX >= originX) {
            if (doubleClick(session, mouseX, mouseY)) {
                drag = DragKind.NONE
                return
            }
        }
        if (hovered && ImGui.isMouseClicked(ImGuiMouseButton.Left) && mouseX >= originX) beginLeftDrag(
            session,
            mouseX,
            mouseY,
            view
        )
        val button = if (drag == DragKind.PAN) ImGuiMouseButton.Middle else ImGuiMouseButton.Left
        if (drag != DragKind.NONE && ImGui.isMouseDown(button)) updateDrag(session, view, mouseX, mouseY, nowNanos)
        if (drag != DragKind.NONE && ImGui.isMouseReleased(button)) finishDrag(session, view)
    }

    private fun doubleClick(session: EditorSession, mouseX: Float, mouseY: Float): Boolean {
        val replay = session.replay ?: return false
        val lane = laneAt(mouseY) ?: return false
        when (lane.kind) {
            LaneKind.CAMERA -> nearestKeyframe(mouseX)?.let {
                replay.seek(it.timeNanos)
                session.selection = Selection(keyframeTimes = setOf(it.timeNanos))
                return true
            }

            LaneKind.CLIPS -> clipAt(mouseX, mouseY)?.let {
                session.execute(SetInOutPoints(it.startNanos, it.endNanos))
                replay.seek(it.startNanos)
                replay.play()
                session.selection = Selection(clipIds = setOf(it.id))
                return true
            }

            LaneKind.MARKERS -> {
                val marker = markerAt(mouseX)
                if (marker != null) replay.seek(marker.nanos) else addMarker(
                    session,
                    snap(nanosAt(mouseX).coerceIn(0L, duration), context.timeline, emptySet(), true)
                )
                return true
            }

            LaneKind.TIMELAPSE -> {
                val mark = timelapseAt(mouseX)
                if (mark != null) replay.seek(mark.nanos) else addTimelapse(
                    session,
                    snap(nanosAt(mouseX).coerceIn(0L, duration), context.timeline, emptySet(), true)
                )
                return true
            }

            LaneKind.POSE -> poseKeyAt(mouseX)?.let {
                replay.seek(it.timeNanos)
                selectPoseEntity(session, it.entityId)
                return true
            }

            LaneKind.PLAYERS -> {
                gameLanes.togglePlayer(mouseY - laneTop(lane.kind), lane.rowHeight)
                return true
            }

            LaneKind.MOMENTS -> {
                val moment = gameLanes.momentAt(session, mouseX)
                if (moment != null) {
                    session.execute(SetInOutPoints(moment.nanos, moment.endNanos))
                    replay.seek(moment.nanos)
                    replay.play()
                    session.selection = Selection(momentIds = setOf(moment.id))
                } else gameLanes.addManual(
                    session,
                    snap(nanosAt(mouseX).coerceIn(0L, duration), context.timeline, emptySet(), true)
                )
                return true
            }

            LaneKind.VIEW -> {
                if (nearestViewKey(mouseX) == null) {
                    addViewKeyframe(
                        session,
                        snap(nanosAt(mouseX).coerceIn(0L, duration), context.timeline, emptySet(), true)
                    )
                    return true
                }
            }

            else -> {
                val valueLane = ValueLane.entries.firstOrNull { it.kind == lane.kind } ?: return false
                if (nearestValueKey(valueLane, mouseX) == null) {
                    addValueKeyframe(
                        session,
                        valueLane,
                        snap(nanosAt(mouseX).coerceIn(0L, duration), context.timeline, emptySet(), true)
                    )
                    return true
                }
            }
        }
        return false
    }

    private fun beginLeftDrag(session: EditorSession, mouseX: Float, mouseY: Float, view: TimelineView) {
        val io = ImGui.getIO()
        val additive = io.keyCtrl
        dragStartMouseX = mouseX
        dragStartMouseY = mouseY
        dragDuplicate = false
        if (mouseY >= navTop()) {
            val left = navX(view.offsetNanos)
            val right = navX(view.offsetNanos + visibleNanos)
            navOriginOffset = view.offsetNanos
            navOriginVisible = visibleNanos
            drag = when {
                mouseX >= left - 4f && mouseX <= left + 5f -> DragKind.NAV_LEFT
                mouseX >= right - 5f && mouseX <= right + 4f -> DragKind.NAV_RIGHT
                mouseX > left && mouseX < right -> DragKind.NAV_THUMB
                else -> {
                    view.offsetNanos =
                        (navNanos(mouseX) - visibleNanos / 2).coerceIn(0L, maxOf(0L, duration - visibleNanos))
                    navOriginOffset = view.offsetNanos
                    DragKind.NAV_THUMB
                }
            }
            return
        }
        if (mouseY < originY + RULER_HEIGHT) {
            val handle = handleAt(session, mouseX, mouseY)
            if (handle != null) {
                drag = handle
                dragCurrentNanos = if (handle == DragKind.IN_POINT) inPoint(session) else outPoint(session)
                return
            }
        } else {
            val lane = laneAt(mouseY)
            if (lane != null && beginLaneDrag(session, lane, mouseX, mouseY, additive, io.keyAlt)) return
            if (lane != null && lane.kind !in NO_BOX_LANES) {
                drag = DragKind.BOX
                if (!additive) session.selection = Selection.NONE
                return
            }
        }
        if (!additive) session.selection = Selection.NONE
        drag = DragKind.SCRUB
        scrubTarget = snapMagnetic(nanosAt(mouseX), view).coerceIn(0L, duration)
        lastScrubSeekNanos = 0L
    }

    private fun beginLaneDrag(
        session: EditorSession,
        lane: Lane,
        mouseX: Float,
        mouseY: Float,
        additive: Boolean,
        duplicate: Boolean
    ): Boolean {
        when (lane.kind) {
            LaneKind.CAMERA -> {
                val hit = nearestKeyframe(mouseX) ?: return false
                val locked = session.project.lane(LaneKind.CAMERA).locked
                val current = session.selection.keyframeTimes
                session.selection =
                    if (hit.timeNanos in current && !additive) session.selection else session.selection.withKeyframe(
                        hit.timeNanos,
                        additive
                    )
                if (locked) return true
                drag = DragKind.KEYFRAMES
                dragTimes = session.selection.keyframeTimes.ifEmpty { setOf(hit.timeNanos) }
                dragDelta = 0L
                dragDuplicate = duplicate
                dragOriginNanos = nanosAt(mouseX)
                return true
            }

            LaneKind.SPEED, LaneKind.FOV, LaneKind.TIME_OF_DAY, LaneKind.SHAKE, LaneKind.FREEZE, LaneKind.SHAKE_FREQUENCY -> {
                val valueLane = ValueLane.entries.first { it.kind == lane.kind }
                val hit = nearestValueKey(valueLane, mouseX) ?: return false
                session.selection = session.selection.withValueKeyframe(valueLane, hit.timeNanos, additive)
                drag = DragKind.VALUE_KEY
                dragLane = valueLane
                dragId = hit.timeNanos
                dragOriginNanos = hit.timeNanos
                dragCurrentNanos = hit.timeNanos
                return true
            }

            LaneKind.VIEW -> {
                val hit = nearestViewKey(mouseX) ?: return false
                session.selection = session.selection.withViewKeyframe(hit.timeNanos, additive)
                drag = DragKind.VIEW_KEY
                dragId = hit.timeNanos
                dragOriginNanos = hit.timeNanos
                dragCurrentNanos = hit.timeNanos
                return true
            }

            LaneKind.CLIPS -> {
                val clip = clipAt(mouseX, mouseY) ?: return false
                val left = xAt(clip.startNanos)
                val right = xAt(clip.endNanos)
                drag = when {
                    mouseX - left <= EDGE_GRAB -> DragKind.CLIP_START
                    right - mouseX <= EDGE_GRAB -> DragKind.CLIP_END
                    else -> DragKind.CLIP_BODY
                }
                dragId = clip.id
                dragClipStart = clip.startNanos
                dragClipEnd = clip.endNanos
                dragOriginNanos = nanosAt(mouseX)
                session.selection = session.selection.withClip(clip.id, additive)
                return true
            }

            LaneKind.MARKERS -> {
                val marker = markerAt(mouseX) ?: return false
                drag = DragKind.MARKER
                dragId = marker.id
                dragOriginNanos = marker.nanos
                dragCurrentNanos = marker.nanos
                session.selection = session.selection.withMarker(marker.id, additive)
                return true
            }

            LaneKind.TIMELAPSE -> {
                val mark = timelapseAt(mouseX) ?: return false
                drag = DragKind.TIMELAPSE
                dragId = mark.id
                dragOriginNanos = mark.nanos
                dragCurrentNanos = mark.nanos
                session.selection = session.selection.withTimelapse(mark.id, additive)
                return true
            }

            LaneKind.POSE -> {
                val hit = poseKeyAt(mouseX) ?: return false
                session.replay?.seek(hit.timeNanos)
                selectPoseEntity(session, hit.entityId)
                drag = DragKind.NONE
                return true
            }

            LaneKind.EVENTS -> {
                val event = eventAt(session, mouseX) ?: return false
                session.replay?.seek(event.nanos)
                drag = DragKind.NONE
                return true
            }

            LaneKind.PLAYERS -> {
                if (!gameLanes.clickPlayers(session, mouseX, mouseY - laneTop(lane.kind), lane.rowHeight)) return false
                drag = DragKind.NONE
                return true
            }

            LaneKind.WORLD -> {
                if (!gameLanes.clickWorld(session, mouseX, mouseY - laneTop(lane.kind), lane.rowHeight)) return false
                drag = DragKind.NONE
                return true
            }

            LaneKind.MOMENTS -> {
                val moment = gameLanes.momentAt(session, mouseX) ?: return false
                session.selection = session.selection.withMoment(moment.id, additive)
                context.selectedEntityId = null
                drag = DragKind.NONE
                return true
            }

            else -> return false
        }
    }

    private fun updateDrag(session: EditorSession, view: TimelineView, mouseX: Float, mouseY: Float, nowNanos: Long) {
        when (drag) {
            DragKind.PAN -> view.offsetNanos =
                (dragOriginNanos - ((mouseX - dragStartMouseX) / width * visibleNanos).toLong()).coerceIn(
                    0L,
                    maxOf(0L, duration - visibleNanos)
                )

            DragKind.SCRUB -> {
                scrubTarget = snapMagnetic(nanosAt(mouseX), view).coerceIn(0L, duration)
                val interval = maxOf(MIN_SCRUB_INTERVAL, (session.replay?.lastSeekDurationNanos ?: 0L) * 3 / 2)
                if (nowNanos - lastScrubSeekNanos >= interval) {
                    lastScrubSeekNanos = nowNanos
                    session.replay?.seek(scrubTarget)
                }
            }

            DragKind.IN_POINT -> dragCurrentNanos =
                snap(nanosAt(mouseX), view, emptySet()).coerceIn(0L, outPoint(session))

            DragKind.OUT_POINT -> dragCurrentNanos =
                snap(nanosAt(mouseX), view, emptySet()).coerceIn(inPoint(session), duration)

            DragKind.KEYFRAMES -> {
                val anchor = dragTimes.minOrNull() ?: 0L
                val raw = anchor + (nanosAt(mouseX) - dragOriginNanos)
                val snapped = snap(raw, view, dragTimes)
                dragDelta = maxOf(-anchor, snapped - anchor)
            }

            DragKind.VALUE_KEY, DragKind.VIEW_KEY, DragKind.MARKER, DragKind.TIMELAPSE -> dragCurrentNanos =
                snap(nanosAt(mouseX), view, emptySet()).coerceIn(0L, duration)

            DragKind.CLIP_BODY -> {
                val clip = session.project.clip(dragId as UUID) ?: return
                val delta = snap(nanosAt(mouseX), view, emptySet()) - snap(dragOriginNanos, view, emptySet())
                val shifted = (clip.startNanos + delta).coerceIn(0L, maxOf(0L, duration - clip.durationNanos))
                dragClipStart = shifted
                dragClipEnd = shifted + clip.durationNanos
            }

            DragKind.CLIP_START -> dragClipStart =
                snap(nanosAt(mouseX), view, emptySet()).coerceIn(0L, dragClipEnd - Nanos.PER_TICK)

            DragKind.CLIP_END -> dragClipEnd =
                snap(nanosAt(mouseX), view, emptySet()).coerceIn(dragClipStart + Nanos.PER_TICK, duration)

            DragKind.NAV_THUMB -> view.offsetNanos =
                (navOriginOffset + navNanos(mouseX) - navNanos(dragStartMouseX)).coerceIn(
                    0L,
                    maxOf(0L, duration - visibleNanos)
                )

            DragKind.NAV_LEFT -> {
                val end = navOriginOffset + navOriginVisible
                val start =
                    (navOriginOffset + navNanos(mouseX) - navNanos(dragStartMouseX)).coerceIn(0L, end - MIN_VISIBLE)
                setVisible(view, start, end - start)
            }

            DragKind.NAV_RIGHT -> {
                val end = (navOriginOffset + navOriginVisible + navNanos(mouseX) - navNanos(dragStartMouseX)).coerceIn(
                    navOriginOffset + MIN_VISIBLE,
                    duration
                )
                setVisible(view, navOriginOffset, end - navOriginOffset)
            }

            else -> Unit
        }
    }

    private fun setVisible(view: TimelineView, offset: Long, visible: Long) {
        val clamped = visible.coerceIn(MIN_VISIBLE, duration)
        view.zoom = duration.toDouble() / clamped
        visibleNanos = clamped
        view.offsetNanos = offset.coerceIn(0L, maxOf(0L, duration - clamped))
    }

    private fun finishDrag(session: EditorSession, view: TimelineView) {
        when (drag) {
            DragKind.SCRUB -> if (scrubTarget >= 0L) session.replay?.seek(scrubTarget)
            DragKind.IN_POINT -> if (dragCurrentNanos != inPoint(session)) session.execute(
                SetInOutPoints(
                    dragCurrentNanos,
                    outPoint(session)
                )
            )

            DragKind.OUT_POINT -> if (dragCurrentNanos != outPoint(session)) session.execute(
                SetInOutPoints(
                    inPoint(
                        session
                    ), dragCurrentNanos
                )
            )

            DragKind.KEYFRAMES -> if (dragDelta != 0L) {
                if (dragDuplicate) {
                    val created = HashSet<Long>()
                    for (time in dragTimes) {
                        val frame = session.project.camera.keyframeAt(time) ?: continue
                        val target = time + dragDelta
                        session.execute(SetCameraKeyframe(target, frame.pose, frame.easing, frame.mode))
                        created += target
                    }
                    session.selection = Selection(keyframeTimes = created)
                } else {
                    session.execute(MoveKeyframes(dragTimes, dragDelta))
                    session.selection = Selection(keyframeTimes = dragTimes.map { it + dragDelta }.toSet())
                }
            }

            DragKind.VALUE_KEY -> {
                val from = dragId as Long
                val lane = dragLane
                if (lane != null && dragCurrentNanos != from) {
                    session.execute(MoveValueKeyframe(lane, from, dragCurrentNanos))
                    session.selection = Selection(valueKeys = setOf(ValueKey(lane, dragCurrentNanos)))
                }
            }

            DragKind.VIEW_KEY -> {
                val from = dragId as Long
                if (dragCurrentNanos != from) {
                    session.execute(MoveViewKeyframe(from, dragCurrentNanos))
                    session.selection = Selection(viewTimes = setOf(dragCurrentNanos))
                }
            }

            DragKind.MARKER -> {
                val marker = session.project.marker(dragId as UUID)
                if (marker != null && dragCurrentNanos != marker.nanos) session.execute(
                    ReplaceMarker(
                        marker.id,
                        marker.copy(nanos = dragCurrentNanos)
                    )
                )
            }

            DragKind.TIMELAPSE -> {
                val mark = session.project.timelapse(dragId as UUID)
                if (mark != null && dragCurrentNanos != mark.nanos) session.execute(
                    ReplaceTimelapse(
                        mark.id,
                        mark.copy(nanos = dragCurrentNanos)
                    )
                )
            }

            DragKind.CLIP_BODY, DragKind.CLIP_START, DragKind.CLIP_END -> {
                val clip = session.project.clip(dragId as UUID)
                if (clip != null && (clip.startNanos != dragClipStart || clip.endNanos != dragClipEnd)) {
                    val updated = clip.trimmed(dragClipStart, dragClipEnd)
                    session.execute(ReplaceClip(clip.id, updated))
                    context.clips.save(updated)
                }
            }

            DragKind.BOX -> boxSelect(session)
            else -> Unit
        }
        drag = DragKind.NONE
        dragId = null
        dragTimes = emptySet()
        dragDelta = 0L
        dragDuplicate = false
        dragLane = null
        scrubTarget = -1L
    }

    private fun boxSelect(session: EditorSession) {
        val x1 = minOf(dragStartMouseX, ImGui.getMousePosX())
        val x2 = maxOf(dragStartMouseX, ImGui.getMousePosX())
        val y1 = minOf(dragStartMouseY, ImGui.getMousePosY())
        val y2 = maxOf(dragStartMouseY, ImGui.getMousePosY())
        if (x2 - x1 < 3f && y2 - y1 < 3f) return
        val from = nanosAt(x1)
        val to = nanosAt(x2)
        val additive = ImGui.getIO().keyCtrl
        var selection = if (additive) session.selection else Selection.NONE
        if (overlaps(y1, y2, LaneKind.CAMERA)) selection =
            selection.copy(keyframeTimes = selection.keyframeTimes + keyTimes.filter { it in from..to })
        for (lane in ValueLane.entries) {
            if (overlaps(y1, y2, lane.kind)) selection = selection.copy(
                valueKeys = selection.valueKeys + (valueKeys[lane] ?: emptyList()).filter { it.timeNanos in from..to }
                    .map { ValueKey(lane, it.timeNanos) })
        }
        if (overlaps(y1, y2, LaneKind.VIEW)) selection =
            selection.copy(viewTimes = selection.viewTimes + viewKeys.filter { it.timeNanos in from..to }
                .map { it.timeNanos })
        if (overlaps(y1, y2, LaneKind.CLIPS)) selection =
            selection.copy(clipIds = selection.clipIds + clips.filter { it.endNanos >= from && it.startNanos <= to }
                .map { it.id })
        if (overlaps(y1, y2, LaneKind.MARKERS)) selection =
            selection.copy(markerIds = selection.markerIds + markers.filter { it.nanos in from..to }.map { it.id })
        if (overlaps(y1, y2, LaneKind.TIMELAPSE)) selection =
            selection.copy(timelapseIds = selection.timelapseIds + timelapses.filter { it.nanos in from..to }
                .map { it.id })
        session.selection = selection
    }

    private fun overlaps(y1: Float, y2: Float, kind: LaneKind): Boolean {
        val top = laneTop(kind)
        return y2 >= top && y1 <= top + laneHeight(kind)
    }

    private fun prepareContext(session: EditorSession) {
        val mouseX = ImGui.getMousePosX()
        val mouseY = ImGui.getMousePosY()
        contextNanos = nanosAt(mouseX).coerceIn(0L, duration)
        contextKeyframe = null
        contextValue = null
        contextView = null
        contextClip = null
        contextMarker = null
        contextTimelapse = null
        contextPose = null
        contextMoment = null
        val lane = laneAt(mouseY)
        contextLane = lane?.kind
        when (lane?.kind) {
            LaneKind.CAMERA -> nearestKeyframe(mouseX)?.let {
                contextKeyframe = it.timeNanos
                if (it.timeNanos !in session.selection.keyframeTimes) session.selection =
                    Selection(keyframeTimes = setOf(it.timeNanos))
            }

            LaneKind.SPEED, LaneKind.FOV, LaneKind.TIME_OF_DAY, LaneKind.SHAKE, LaneKind.FREEZE, LaneKind.SHAKE_FREQUENCY -> {
                val valueLane = ValueLane.entries.first { it.kind == lane.kind }
                nearestValueKey(valueLane, mouseX)?.let {
                    contextValue = ValueKey(valueLane, it.timeNanos)
                    valueHolder[0] = it.value.toFloat()
                    if (contextValue !in session.selection.valueKeys) session.selection =
                        Selection(valueKeys = setOf(ValueKey(valueLane, it.timeNanos)))
                }
            }

            LaneKind.VIEW -> nearestViewKey(mouseX)?.let {
                contextView = it.timeNanos
                if (it.timeNanos !in session.selection.viewTimes) session.selection =
                    Selection(viewTimes = setOf(it.timeNanos))
            }

            LaneKind.CLIPS -> clipAt(mouseX, mouseY)?.let {
                contextClip = it.id
                if (it.id !in session.selection.clipIds) session.selection = Selection(clipIds = setOf(it.id))
            }

            LaneKind.MARKERS -> markerAt(mouseX)?.let {
                contextMarker = it.id
                renameBuffer.set(it.label)
                if (it.id !in session.selection.markerIds) session.selection = Selection(markerIds = setOf(it.id))
            }

            LaneKind.TIMELAPSE -> timelapseAt(mouseX)?.let {
                contextTimelapse = it.id
                if (it.id !in session.selection.timelapseIds) session.selection = Selection(timelapseIds = setOf(it.id))
            }

            LaneKind.POSE -> poseKeyAt(mouseX)?.let { contextPose = it }

            LaneKind.MOMENTS -> gameLanes.momentAt(session, mouseX)?.let {
                contextMoment = it.id
                gameLanes.beginMomentMenu(it)
                if (it.id !in session.selection.momentIds) session.selection = Selection(momentIds = setOf(it.id))
            }

            else -> Unit
        }
    }

    private fun contextMenu(session: EditorSession) {
        val replay = session.replay ?: return
        val keyframe = contextKeyframe
        val value = contextValue
        val viewTime = contextView
        val clipId = contextClip
        val markerId = contextMarker
        val timelapseId = contextTimelapse
        val pose = contextPose
        val momentId = contextMoment
        when {
            keyframe != null -> keyframeMenu(session, replay, keyframe)
            momentId != null -> gameLanes.moments(session).firstOrNull { it.id == momentId }
                ?.let { gameLanes.momentMenu(session, it) }

            value != null -> valueMenu(session, value)
            viewTime != null -> viewMenu(session, replay, viewTime)
            clipId != null -> session.project.clip(clipId)?.let { clipMenu(session, replay, it) }
            markerId != null -> session.project.marker(markerId)?.let { markerMenu(session, it) }
            timelapseId != null -> session.project.timelapse(timelapseId)?.let { timelapseMenu(session, replay, it) }
            pose != null -> poseMenu(session, replay, pose)

            else -> emptyMenu(session, replay)
        }
    }

    private fun keyframeMenu(session: EditorSession, replay: ReplaySession, time: Long) {
        val frame = session.project.camera.keyframeAt(time) ?: return
        val targets = session.selection.keyframeTimes.ifEmpty { setOf(time) }
        Widgets.mutedText("Keyframe ${TimeFormat.clock(time)}${if (targets.size > 1) "  (${targets.size} selected)" else ""}")
        ImGui.separator()
        if (ImGui.menuItem("Go to keyframe")) replay.seek(time)
        if (ImGui.menuItem("Update from current view")) session.execute(
            SetCameraKeyframe(
                time,
                context.host.camera.currentPose(),
                frame.easing,
                frame.mode
            )
        )
        if (ImGui.menuItem("Duplicate at playhead", "Ctrl+D")) {
            session.execute(SetCameraKeyframe(replay.positionNanos, frame.pose, frame.easing, frame.mode))
            session.selection = Selection(keyframeTimes = setOf(replay.positionNanos))
        }
        ImGui.separator()
        if (ImGui.beginMenu("Interpolation")) {
            for (mode in SegmentMode.entries) {
                if (ImGui.menuItem(mode.label, "", frame.mode == mode)) session.execute(SetKeyframeMode(targets, mode))
            }
            ImGui.endMenu()
        }
        if (ImGui.beginMenu("Easing")) {
            EasingWidgets.menu(frame.easing)?.let { session.execute(SetKeyframeEasing(targets, it)) }
            ImGui.endMenu()
        }
        if (ImGui.menuItem("Easy ease", "F9")) session.execute(EaseKeyframes.easyEase(targets, emptySet()))
        if (ImGui.menuItem("Ease in", "Shift+F9")) session.execute(EaseKeyframes.easeIn(targets, emptySet()))
        if (ImGui.menuItem("Ease out", "Ctrl+Shift+F9")) session.execute(EaseKeyframes.easeOut(targets, emptySet()))
        if (ImGui.menuItem("Edit curves in Graph Editor")) context.openPanel("Graph Editor")
        ImGui.separator()
        if (ImGui.menuItem(if (targets.size > 1) "Delete ${targets.size} keyframes" else "Delete keyframe", "Del")) {
            session.execute(RemoveKeyframes(targets))
            session.selection = Selection.NONE
        }
    }

    private fun valueMenu(session: EditorSession, key: ValueKey) {
        val lane = key.lane
        val frame = session.project.valueTrack(lane).at(key.nanos) ?: return
        Widgets.mutedText("${lane.label} keyframe ${TimeFormat.clock(key.nanos)}")
        ImGui.separator()
        ImGui.setNextItemWidth(200f)
        Widgets.slider(
            "##value",
            valueHolder[0],
            lane.min.toFloat(),
            lane.max.toFloat(),
            lane.format,
            EditorFonts.px(200f),
            labelOf = { lane.format(it.toDouble()) })?.let {
            valueHolder[0] = it
            session.execute(SetValueKeyframe(lane, key.nanos, it.toDouble(), frame.mode, frame.easing))
        }
        val presets = VALUE_PRESETS[lane]
        if (presets != null) {
            for ((index, preset) in presets.withIndex()) {
                if (index > 0) ImGui.sameLine()
                if (Widgets.smallButton(lane.format(preset))) {
                    valueHolder[0] = preset.toFloat()
                    session.execute(SetValueKeyframe(lane, key.nanos, preset, frame.mode, frame.easing))
                }
            }
        }
        ImGui.separator()
        if (ImGui.beginMenu("Ramp")) {
            for (mode in listOf(SegmentMode.LINEAR, SegmentMode.CATMULL_ROM, SegmentMode.HOLD)) {
                if (ImGui.menuItem(mode.label, "", frame.mode == mode)) session.execute(
                    SetValueKeyframe(
                        lane,
                        key.nanos,
                        frame.value,
                        mode,
                        frame.easing
                    )
                )
            }
            ImGui.endMenu()
        }
        if (ImGui.beginMenu("Easing")) {
            EasingWidgets.menu(frame.easing)?.let { session.execute(SetValueKeyframeEasing(setOf(key), it)) }
            ImGui.endMenu()
        }
        if (ImGui.menuItem("Easy ease", "F9")) session.execute(EaseKeyframes.easyEase(emptySet(), setOf(key)))
        if (ImGui.menuItem("Edit curves in Graph Editor")) context.openPanel("Graph Editor")
        ImGui.separator()
        if (ImGui.menuItem("Delete keyframe", "Del")) {
            session.execute(RemoveValueKeyframes(setOf(key)))
            session.selection = Selection.NONE
        }
    }

    private fun viewMenu(session: EditorSession, replay: ReplaySession, time: Long) {
        val frame = session.project.views.at(time) ?: return
        val targets = session.selection.viewTimes.ifEmpty { setOf(time) }
        Widgets.mutedText("${viewLabel(frame.value)} from ${TimeFormat.clock(time)}")
        ImGui.separator()
        if (ImGui.menuItem("Go to")) replay.seek(time)
        if (ImGui.menuItem("Update from current camera settings")) session.execute(
            SetViewKeyframe(
                time,
                ViewState.capture(context.host.camera.settings),
                frame.mode,
                frame.easing
            )
        )
        if (ImGui.menuItem("Apply to camera now")) {
            frame.value.applyTo(context.host.camera.settings)
            context.host.camera.apply()
        }
        ImGui.separator()
        if (ImGui.beginMenu("Interpolation")) {
            for (mode in SegmentMode.entries) {
                if (ImGui.menuItem(mode.label, "", frame.mode == mode)) session.execute(
                    SetViewKeyframeMode(
                        targets,
                        mode
                    )
                )
            }
            ImGui.endMenu()
        }
        Widgets.tooltip("Hold snaps to this keyframe's camera mode/target/settings until the next one; Smooth blends orbit/follow/chase numbers into the next keyframe when it shares the same mode and target")
        ImGui.separator()
        if (ImGui.menuItem("Delete view keyframe", "Del")) {
            session.execute(RemoveViewKeyframes(setOf(time)))
            session.selection = Selection.NONE
        }
    }

    private fun clipMenu(session: EditorSession, replay: ReplaySession, clip: Clip) {
        Widgets.mutedText(clip.title)
        ImGui.separator()
        if (ImGui.menuItem("Play clip")) {
            session.execute(SetInOutPoints(clip.startNanos, clip.endNanos))
            replay.seek(clip.startNanos)
            replay.play()
        }
        if (ImGui.menuItem("Set in/out to clip")) session.execute(SetInOutPoints(clip.startNanos, clip.endNanos))
        if (ImGui.menuItem("Go to start")) replay.seek(clip.startNanos)
        ImGui.separator()
        if (ImGui.menuItem("Delete clip", "Del")) {
            session.execute(RemoveClip(clip.id))
            context.clips.delete(clip.id)
            session.selection = Selection.NONE
        }
    }

    private fun markerMenu(session: EditorSession, marker: TimelineMarker) {
        Widgets.mutedText("Marker ${TimeFormat.clock(marker.nanos)}")
        ImGui.separator()
        ImGui.setNextItemWidth(180f)
        if (ImGui.inputText("##rename", renameBuffer, imgui.flag.ImGuiInputTextFlags.EnterReturnsTrue)) {
            session.execute(ReplaceMarker(marker.id, marker.copy(label = renameBuffer.get())))
            ImGui.closeCurrentPopup()
        }
        for ((index, color) in MARKER_COLORS.withIndex()) {
            if (index > 0) ImGui.sameLine()
            if (Widgets.colorSwatch("color$index", color)) session.execute(
                ReplaceMarker(
                    marker.id,
                    marker.copy(color = color)
                )
            )
        }
        if (ImGui.beginMenu("Type")) {
            for (kind in MarkerKind.entries) if (ImGui.menuItem(kind.label, "", marker.kind == kind)) session.execute(
                ReplaceMarker(
                    marker.id,
                    marker.copy(kind = kind, color = if (marker.kind == kind) marker.color else kind.color)
                )
            )
            ImGui.endMenu()
        }
        ImGui.separator()
        if (ImGui.menuItem("Go to marker")) session.replay?.seek(marker.nanos)
        if (ImGui.menuItem("Delete marker", "Del")) {
            session.execute(RemoveMarker(marker.id))
            session.selection = Selection.NONE
        }
    }

    private fun timelapseMenu(session: EditorSession, replay: ReplaySession, mark: TimelapseMark) {
        Widgets.mutedText("Timelapse ${TimeFormat.clock(mark.nanos)}")
        ImGui.separator()
        ImGui.setNextItemWidth(160f)
        Widgets.doubleSlider("##skip", mark.skipNanos / Nanos.PER_SECOND.toDouble(), 0.1, 300.0, "+%.1fs")?.let {
            session.execute(ReplaceTimelapse(mark.id, mark.copy(skipNanos = (it * Nanos.PER_SECOND).toLong())))
        }
        ImGui.separator()
        if (ImGui.menuItem("Go to")) replay.seek(mark.nanos)
        if (ImGui.menuItem("Delete timelapse", "Del")) {
            session.execute(RemoveTimelapse(mark.id))
            session.selection = Selection.NONE
        }
    }

    private fun emptyMenu(session: EditorSession, replay: ReplaySession) {
        val at = contextNanos
        Widgets.mutedText(TimeFormat.clock(at))
        ImGui.separator()
        if (ImGui.menuItem("Jump here")) replay.seek(at)
        if (ImGui.menuItem("Add camera keyframe here", "Ctrl+K")) {
            replay.seek(at)
            session.execute(
                SetCameraKeyframe(
                    at,
                    context.host.camera.currentPose(),
                    session.defaultEasing,
                    session.defaultKeyframeMode
                )
            )
            session.selection = Selection(keyframeTimes = setOf(at))
        }
        val contextValueLane = ValueLane.entries.firstOrNull { it.kind == contextLane }
        if (contextValueLane != null && ImGui.menuItem("Add ${contextValueLane.label.lowercase()} keyframe here")) addValueKeyframe(
            session,
            contextValueLane,
            at
        )
        if (contextLane == LaneKind.VIEW && ImGui.menuItem("Add view keyframe here")) addViewKeyframe(session, at)
        if (contextLane == LaneKind.TIMELAPSE && ImGui.menuItem("Add timelapse skip here")) addTimelapse(session, at)
        if (ImGui.beginMenu("Add keyframe")) {
            for (lane in ValueLane.entries) if (ImGui.menuItem("${lane.label} keyframe")) addValueKeyframe(
                session,
                lane,
                at
            )
            if (ImGui.menuItem("View keyframe")) addViewKeyframe(session, at)
            ImGui.endMenu()
        }
        if (contextLane == LaneKind.MOMENTS && ImGui.menuItem("Add moment here")) gameLanes.addManual(session, at)
        if (ImGui.menuItem("Add marker here", "M")) addMarker(session, at)
        if (ImGui.menuItem("Add timelapse skip here")) addTimelapse(session, at)
        ImGui.separator()
        if (ImGui.menuItem("Set in point here", "I")) session.execute(SetInOutPoints(at, maxOf(at, outPoint(session))))
        if (ImGui.menuItem("Set out point here", "O")) session.execute(SetInOutPoints(minOf(inPoint(session), at), at))
        if (ImGui.menuItem("Clip from in/out")) clipFromInOut(session)
        ImGui.separator()
        if (ImGui.menuItem("Zoom to fit")) {
            context.timeline.zoom = 1.0
            context.timeline.offsetNanos = 0L
        }
        if (ImGui.menuItem("Delete selection", "Del", false, !session.selection.isEmpty)) deleteSelection(session)
    }

    private fun addMarker(session: EditorSession, nanos: Long) {
        val marker = TimelineMarker(
            UUID.randomUUID(),
            nanos,
            "Marker ${session.project.markers.size + 1}",
            MARKER_COLORS[session.project.markers.size % MARKER_COLORS.size]
        )
        session.execute(AddMarker(marker))
        session.selection = Selection(markerIds = setOf(marker.id))
    }

    private fun addValueKeyframe(session: EditorSession, lane: ValueLane, nanos: Long) {
        val current = currentValue(session, lane).coerceIn(lane.min, lane.max)
        session.execute(
            SetValueKeyframe(
                lane,
                nanos,
                current,
                if (lane == ValueLane.TIME_OF_DAY) SegmentMode.LINEAR else SegmentMode.LINEAR
            )
        )
        session.selection = Selection(valueKeys = setOf(ValueKey(lane, nanos)))
        val state = session.project.lane(lane.kind)
        if (state.muted) session.execute(SetLaneState(lane.kind, state.copy(muted = false)))
    }

    private fun currentValue(session: EditorSession, lane: ValueLane): Double = when (lane) {
        ValueLane.SPEED -> Math.abs(session.replay?.speed ?: 1.0)
        ValueLane.FOV -> context.host.camera.currentPose().fov
        ValueLane.TIME_OF_DAY -> (context.host.worldTimeOfDay()
            ?: Math.floorMod(session.replay?.shadow?.world?.timeOfDay ?: 6000L, 24000L)).toDouble()

        ValueLane.SHAKE -> context.host.camera.settings.shakeStrength
        ValueLane.FREEZE -> ValueLane.FREEZE.default
        ValueLane.SHAKE_FREQUENCY -> context.host.camera.settings.shakeFrequencyHz
    }

    private fun addViewKeyframe(session: EditorSession, nanos: Long) {
        session.execute(SetViewKeyframe(nanos, ViewState.capture(context.host.camera.settings)))
        session.selection = Selection(viewTimes = setOf(nanos))
        val state = session.project.lane(LaneKind.VIEW)
        if (state.muted) session.execute(SetLaneState(LaneKind.VIEW, state.copy(muted = false)))
    }

    private fun addTimelapse(session: EditorSession, nanos: Long) {
        val mark = TimelapseMark(UUID.randomUUID(), nanos, DEFAULT_TIMELAPSE_SKIP)
        session.execute(AddTimelapse(mark))
        session.selection = Selection(timelapseIds = setOf(mark.id))
        val state = session.project.lane(LaneKind.TIMELAPSE)
        if (state.muted) session.execute(SetLaneState(LaneKind.TIMELAPSE, state.copy(muted = false)))
    }

    private fun clipFromInOut(session: EditorSession) {
        val start = inPoint(session)
        val end = outPoint(session)
        if (end - start < Nanos.PER_TICK) {
            context.status("Set in and out points first (I / O)")
            return
        }
        val project = session.project
        val clip =
            Clip(UUID.randomUUID(), project.recording, project.sessionId, start, end, "Clip ${project.clips.size + 1}")
        session.execute(AddClip(clip))
        context.clips.save(clip)
        session.selection = Selection(clipIds = setOf(clip.id))
        context.status("Added ${clip.title}")
    }

    private fun deleteSelection(session: EditorSession) {
        val selection = session.selection
        if (selection.isEmpty) return
        for (id in selection.clipIds) context.clips.delete(id)
        session.deleteSelection()
    }

    private fun nearestKeyframe(mouseX: Float): CameraKeyframe? {
        var best: CameraKeyframe? = null
        var bestDistance = KEY_RADIUS + 4f
        for (frame in keyframes) {
            val distance = Math.abs(xAt(frame.timeNanos) - mouseX)
            if (distance <= bestDistance) {
                best = frame
                bestDistance = distance
            }
        }
        return best
    }

    private fun nearestValueKey(lane: ValueLane, mouseX: Float): Keyframe<Double>? =
        (valueKeys[lane] ?: emptyList()).minByOrNull { Math.abs(xAt(it.timeNanos) - mouseX) }
            ?.takeIf { Math.abs(xAt(it.timeNanos) - mouseX) <= 9f }

    private fun nearestViewKey(mouseX: Float): Keyframe<ViewState>? =
        viewKeys.lastOrNull { xAt(it.timeNanos) - 4f <= mouseX }?.takeIf { frame ->
            val index = viewKeys.indexOf(frame)
            val next = viewKeys.getOrNull(index + 1)?.timeNanos ?: duration
            mouseX <= xAt(next) + 2f
        }

    private fun clipAt(mouseX: Float, mouseY: Float): Clip? {
        val top = laneTop(LaneKind.CLIPS)
        if (mouseY < top || mouseY >= top + laneHeight(LaneKind.CLIPS)) return null
        return clips.lastOrNull { mouseX >= xAt(it.startNanos) - 2f && mouseX <= xAt(it.endNanos) + 2f }
    }

    private fun markerAt(mouseX: Float): TimelineMarker? =
        markers.minByOrNull { Math.abs(xAt(it.nanos) + 4f - mouseX) }
            ?.takeIf { Math.abs(xAt(it.nanos) + 4f - mouseX) <= 10f }

    private fun timelapseAt(mouseX: Float): TimelapseMark? =
        timelapses.minByOrNull { Math.abs(xAt(it.nanos) + 4f - mouseX) }
            ?.takeIf { Math.abs(xAt(it.nanos) + 4f - mouseX) <= 10f }

    private fun eventAt(session: EditorSession, mouseX: Float): TimelineEvent? =
        session.events.events.minByOrNull { Math.abs(xAt(it.nanos) - mouseX) }
            ?.takeIf { Math.abs(xAt(it.nanos) - mouseX) <= 6f }

    private fun followPlayhead(positionNanos: Long, view: TimelineView) {
        if (positionNanos < view.offsetNanos || positionNanos > view.offsetNanos + visibleNanos * 9 / 10) {
            view.offsetNanos = (positionNanos - visibleNanos / 10).coerceIn(0L, maxOf(0L, duration - visibleNanos))
        }
    }

    private fun zoomAround(view: TimelineView, factor: Double, anchorNanos: Long) {
        val before = visibleNanos
        view.zoom = (view.zoom * factor).coerceIn(1.0, duration.toDouble() / MIN_VISIBLE)
        val after = maxOf(1L, (duration / view.zoom).toLong())
        val ratio = (anchorNanos - view.offsetNanos).toDouble() / before
        view.offsetNanos = (anchorNanos - (after * ratio).toLong()).coerceIn(0L, maxOf(0L, duration - after))
        visibleNanos = after
    }

    private fun snapMagnetic(nanos: Long, view: TimelineView): Long = snap(nanos, view, emptySet(), grid = false)

    private fun snap(nanos: Long, view: TimelineView, exclude: Set<Long>, grid: Boolean = true): Long {
        if (!view.snapToTicks || ImGui.getIO().keyShift) return nanos
        val frame = view.frameNanos()
        var best = if (grid) Math.round(nanos.toDouble() / frame) * frame else nanos
        val tolerance = (SNAP_PIXELS / width * visibleNanos).toLong()
        var bestDistance = tolerance
        val playhead = context.replay?.positionNanos
        val candidates = ArrayList<Long>(8)
        if (playhead != null) candidates += playhead
        val session = context.session
        if (session != null) {
            candidates += inPoint(session)
            candidates += outPoint(session)
        }
        for (time in keyTimes) if (time !in exclude) candidates += time
        for (marker in markers) candidates += marker.nanos
        for (candidate in candidates) {
            val distance = Math.abs(candidate - nanos)
            if (distance <= bestDistance) {
                best = candidate
                bestDistance = distance
            }
        }
        return best
    }

    private fun xAt(nanos: Long): Float =
        originX + ((nanos - context.timeline.offsetNanos).toDouble() / visibleNanos * width).toFloat()

    private fun nanosAt(x: Float): Long = context.timeline.offsetNanos + ((x - originX) / width * visibleNanos).toLong()

    private fun rulerStep(view: TimelineView): Long {
        val minimumPixels = 90f
        val target = (minimumPixels / width * visibleNanos).toLong()
        return RULER_STEPS.firstOrNull { it >= target } ?: RULER_STEPS.last()
    }

    private fun modeColor(mode: SegmentMode): EditorTheme.Rgb = when (mode) {
        SegmentMode.CATMULL_ROM -> EditorTheme.KEYFRAME_SMOOTH
        SegmentMode.LINEAR -> EditorTheme.KEYFRAME_LINEAR
        SegmentMode.BEZIER -> EditorTheme.KEYFRAME_BEZIER
        SegmentMode.HOLD -> EditorTheme.KEYFRAME_HOLD
    }

    private companion object {
        val DEFAULT_TIMELAPSE_SKIP = 5L * Nanos.PER_SECOND
        val CORE_LANES = setOf(LaneKind.CAMERA, LaneKind.CLIPS, LaneKind.MARKERS, LaneKind.EVENTS)
        val NO_BOX_LANES = setOf(LaneKind.EVENTS, LaneKind.PLAYERS, LaneKind.WORLD, LaneKind.MOMENTS)
        val MARKER_ICONS = mapOf(
            MarkerKind.MOMENT to Icon.BOOKMARK,
            MarkerKind.SHOT to Icon.FILM,
            MarkerKind.PLAYER to Icon.PERSON,
            MarkerKind.EVENT to Icon.TARGET,
            MarkerKind.CAMERA to Icon.CAMERA,
        )
        val LANE_ICONS = mapOf(
            LaneKind.CAMERA to Icon.PATH,
            LaneKind.SPEED to Icon.GAUGE,
            LaneKind.FOV to Icon.APERTURE,
            LaneKind.TIME_OF_DAY to Icon.SUN,
            LaneKind.SHAKE to Icon.WAVE,
            LaneKind.SHAKE_FREQUENCY to Icon.WAVE,
            LaneKind.VIEW to Icon.EYE,
            LaneKind.FREEZE to Icon.SNOWFLAKE,
            LaneKind.CLIPS to Icon.FILM,
            LaneKind.MARKERS to Icon.MARKER,
            LaneKind.TIMELAPSE to Icon.FAST_FORWARD,
            LaneKind.POSE to Icon.PERSON,
            LaneKind.EVENTS to Icon.CLOCK,
            LaneKind.PLAYERS to Icon.PERSON,
            LaneKind.WORLD to Icon.GLOBE,
            LaneKind.MOMENTS to Icon.BOOKMARK,
        )
        val BASE_LANES = listOf(
            Lane(LaneKind.CAMERA, "Camera", 30f, EditorTheme.KEYFRAME_SMOOTH),
            Lane(LaneKind.SPEED, "Speed", 28f, EditorTheme.SUCCESS),
            Lane(LaneKind.FOV, "FOV", 26f, EditorTheme.KEYFRAME_BEZIER),
            Lane(LaneKind.TIME_OF_DAY, "Time of day", 26f, EditorTheme.WARNING),
            Lane(LaneKind.SHAKE, "Shake", 26f, EditorTheme.RECORD),
            Lane(LaneKind.SHAKE_FREQUENCY, "Shake Hz", 24f, EditorTheme.RECORD),
            Lane(LaneKind.VIEW, "View", 26f, EditorTheme.ACCENT_TEXT),
            Lane(LaneKind.FREEZE, "Freeze", 24f, EditorTheme.TIMECODE),
            Lane(LaneKind.CLIPS, "Clips", 32f, EditorTheme.CLIP_SELECTED),
            Lane(LaneKind.MARKERS, "Markers", 24f, EditorTheme.MARKER),
            Lane(LaneKind.TIMELAPSE, "Timelapse", 22f, EditorTheme.WARNING),
            Lane(LaneKind.POSE, "Poses", 22f, EditorTheme.PURPLE),
            Lane(LaneKind.EVENTS, "Events", 22f, EditorTheme.EVENT_OTHER),
        )
        val RULER_STEPS = longArrayOf(
            Nanos.ofMillis(50),
            Nanos.ofMillis(100),
            Nanos.ofMillis(200),
            Nanos.ofMillis(500),
            Nanos.ofSeconds(1),
            Nanos.ofSeconds(2),
            Nanos.ofSeconds(5),
            Nanos.ofSeconds(10),
            Nanos.ofSeconds(15),
            Nanos.ofSeconds(30),
            Nanos.ofSeconds(60),
            Nanos.ofSeconds(120),
            Nanos.ofSeconds(300),
            Nanos.ofSeconds(600),
            Nanos.ofSeconds(1800),
        )
        val VALUE_PRESETS = mapOf(
            ValueLane.SPEED to doubleArrayOf(0.25, 0.5, 1.0, 2.0, 4.0),
            ValueLane.FOV to doubleArrayOf(30.0, 50.0, 70.0, 90.0, 110.0),
            ValueLane.TIME_OF_DAY to doubleArrayOf(0.0, 6000.0, 12000.0, 18000.0),
            ValueLane.SHAKE to doubleArrayOf(0.0, 0.5, 1.0, 2.0),
            ValueLane.FREEZE to doubleArrayOf(0.5, 1.0, 2.0, 5.0),
            ValueLane.SHAKE_FREQUENCY to doubleArrayOf(0.5, 1.6, 4.0, 10.0),
        )
        val VALUE_ADD_TOOLTIPS = mapOf(
            ValueLane.SPEED to "Add speed keyframe with the current playback speed",
            ValueLane.FOV to "Add FOV keyframe with the current field of view",
            ValueLane.TIME_OF_DAY to "Add time-of-day keyframe with the current world time",
            ValueLane.SHAKE to "Add camera shake keyframe with the current shake strength",
            ValueLane.FREEZE to "Freeze the replay here for a number of seconds during playback and export",
            ValueLane.SHAKE_FREQUENCY to "Add shake frequency keyframe with the current frequency",
        )
        val VALUE_HINTS = mapOf(
            ValueLane.SPEED to "Speed ramps: add keyframes to slow down or speed up playback between them.",
            ValueLane.FOV to "FOV keyframes zoom the lens over time, independent of the camera path.",
            ValueLane.TIME_OF_DAY to "Time-of-day keyframes drive the sun and lighting; great for timelapses.",
            ValueLane.SHAKE to "Shake keyframes ramp handheld camera shake in and out.",
            ValueLane.SHAKE_FREQUENCY to "Controls how fast the shake wobbles; pair with the Shake lane for a rougher or smoother handheld feel.",
            ValueLane.FREEZE to "Freeze keyframes hold the replay still for a few seconds while the camera keeps moving.",
        )
        val SEGMENT_COLORS =
            listOf(EditorTheme.ACCENT_TEXT, EditorTheme.WARNING, EditorTheme.SUCCESS, EditorTheme.KEYFRAME_BEZIER)
        val MARKER_COLORS = intArrayOf(0x59B36A, 0x66D4CF, 0xFFC94D, 0xE5484D, 0xC792EA, 0xF5A623, 0x8A8A8A, 0xFFFFFF)
        val RULER_HEIGHT: Float get() = EditorFonts.px(24f)
        val HEADER_WIDTH: Float get() = EditorFonts.px(156f)
        val NAV_HEIGHT: Float get() = EditorFonts.px(12f)
        val NAV_GAP: Float get() = EditorFonts.px(6f)
        val TOOL_SIZE: Float get() = EditorFonts.px(26f)
        val KEY_RADIUS: Float get() = EditorFonts.px(6.5f)
        val EDGE_GRAB: Float get() = EditorFonts.px(7f)
        val HANDLE_WIDTH: Float get() = EditorFonts.px(8f)
        val HANDLE_HEIGHT: Float get() = EditorFonts.px(12f)
        val SNAP_PIXELS: Float get() = EditorFonts.px(6f)
        const val CURVE_STEP = 4f
        const val MINOR_DIVISIONS = 5L
        val EASE_GLYPH_MIN_WIDTH: Float get() = EditorFonts.px(28f)
        val MIN_VISIBLE: Long = Nanos.ofMillis(250)
        val MIN_SCRUB_INTERVAL = Nanos.ofMillis(4)
    }
}
