package gg.sona.recast.editor.imgui

import gg.sona.recast.camera.Easing
import gg.sona.recast.camera.track.Extrapolation
import gg.sona.recast.camera.track.SegmentMode
import gg.sona.recast.core.time.Nanos
import gg.sona.recast.editor.*
import gg.sona.recast.editor.commands.*
import imgui.ImDrawList
import imgui.ImGui
import imgui.flag.ImGuiKey
import imgui.flag.ImGuiMouseButton
import imgui.flag.ImGuiMouseCursor
import imgui.flag.ImGuiWindowFlags
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class GraphEditorPanel(private val context: EditorContext) :
    AbstractPanel("Graph Editor", DockArea.BOTTOM, Icon.GRAPH, flags = ImGuiWindowFlags.NoScrollbar) {

    enum class Mode(val label: String) { VALUE("Value"), SPEED("Speed") }

    private enum class Drag { NONE, KEYS, HANDLE, BOX, PAN, SCRUB, STRETCH }

    private class Range(val lo: Double, val hi: Double)

    private class Hit(val channel: GraphChannel, val key: GraphChannel.Key, val handleOut: Boolean? = null)

    private var mode = Mode.VALUE
    private var normalized = true
    private var selectedOnly = false
    private var linked = true
    private var snap = true
    private var showHandles = true
    private var ownOffset = 0L
    private var ownVisible = Nanos.ofSeconds(10)
    private var valueMin = -10.0
    private var valueMax = 10.0
    private val hidden = HashSet<String>()
    private var solo: String? = null
    private var hoveredChannel: GraphChannel? = null

    private var originX = 0f
    private var originY = 0f
    private var width = 1f
    private var height = 1f
    private var listX = 0f
    private var duration = 1L
    private var offsetNanos = 0L
    private var visibleNanos = 1L
    private val ranges = HashMap<String, Range>()
    private val visibleChannels = ArrayList<GraphChannel>()
    private val keyCache = HashMap<String, List<GraphChannel.Key>>()

    private var drag = Drag.NONE
    private var dragHit: Hit? = null
    private var dragStartX = 0f
    private var dragStartY = 0f
    private var dragAppliedNanos = 0L
    private var dragAnchorNanos = 0L
    private var dragStartValues = HashMap<Long, Double>()
    private var dragAxis = 0
    private var stretchLeft = false
    private var stretchOriginalSpan = 0L
    private var boxX = 0f
    private var boxY = 0f
    private var lastScrub = -1L
    private var contextHit: Hit? = null
    private var contextNanos = 0L
    private var fitRequested = true
    private var lastFitVersion = -1L

    override fun content(frame: FrameContext) {
        val session = context.session
        val replay = session?.replay
        if (session == null || replay == null) {
            Widgets.emptyState("No replay open", "Open a recording to edit its animation curves", Icon.GRAPH)
            return
        }
        duration = maxOf(1L, replay.durationNanos)
        toolbar(session)
        listX = ImGui.getCursorScreenPosX()
        originX = listX + LIST_WIDTH
        originY = ImGui.getCursorScreenPosY()
        width = maxOf(1f, ImGui.getContentRegionAvailX() - LIST_WIDTH)
        height = maxOf(1f, ImGui.getContentRegionAvailY() - 2f)
        syncView()
        collectChannels(session)
        computeRanges(session.project)
        if (fitRequested || lastFitVersion < 0L) {
            fitAll(session, time = fitRequested || !linked)
            fitRequested = false
        }
        lastFitVersion = session.commands.version

        val drawList = ImGui.getWindowDrawList()
        drawList.addRectFilled(listX, originY, originX + width, originY + height, EditorTheme.PANEL_SUNKEN.u32)
        channelList(session)

        ImGui.setCursorScreenPos(originX, originY)
        ImGui.invisibleButton("graph-canvas", width, height)
        val hovered = ImGui.isItemHovered()
        val active = ImGui.isItemActive()
        drawList.pushClipRect(originX, originY, originX + width, originY + height, true)
        drawGrid(drawList)
        drawRuler(drawList)
        drawCurves(drawList, session)
        drawKeys(drawList, session)
        drawStretchBrackets(drawList, session)
        drawPlayhead(drawList, if (drag == Drag.SCRUB && lastScrub >= 0L) lastScrub else replay.positionNanos)
        if (drag == Drag.BOX) drawBox(drawList)
        EditorFonts.with(EditorFonts.small) {
            drawList.addText(
                originX + EditorFonts.px(10f),
                originY + height - ImGui.getFontSize() - EditorFonts.px(8f),
                EditorTheme.TEXT_DIM.u32,
                statusText(session)
            )
        }
        drawList.popClipRect()
        drawAxis(drawList)

        if (hovered || active || drag != Drag.NONE) handleInput(session, hovered)
        if (hovered && drag == Drag.NONE) hoverFeedback(session)
        if (hovered && ImGui.isMouseClicked(ImGuiMouseButton.Right)) {
            contextHit = hitTest(session, ImGui.getMousePosX(), ImGui.getMousePosY())
            contextNanos = nanosAt(ImGui.getMousePosX())
        }
        if (ImGui.beginPopupContextItem("graph-context")) {
            contextMenu(session)
            ImGui.endPopup()
        }
        keyboard(session, hovered)
        ImGui.setCursorScreenPos(listX, originY + height)
        ImGui.dummy(1f, 1f)
    }

    private fun syncView() {
        if (linked) {
            val view = context.timeline
            visibleNanos = maxOf(1L, (duration / view.zoom).toLong())
            view.offsetNanos = view.offsetNanos.coerceIn(0L, maxOf(0L, duration - visibleNanos))
            offsetNanos = view.offsetNanos
        } else {
            ownVisible = ownVisible.coerceIn(Nanos.ofMillis(200), duration)
            ownOffset = ownOffset.coerceIn(0L, maxOf(0L, duration - ownVisible))
            visibleNanos = ownVisible
            offsetNanos = ownOffset
        }
    }

    private fun setVisible(offset: Long, visible: Long) {
        val clampedVisible = visible.coerceIn(Nanos.ofMillis(200), duration)
        val clampedOffset = offset.coerceIn(0L, maxOf(0L, duration - clampedVisible))
        if (linked) {
            context.timeline.zoom = duration.toDouble() / clampedVisible
            context.timeline.offsetNanos = clampedOffset
        } else {
            ownVisible = clampedVisible
            ownOffset = clampedOffset
        }
        visibleNanos = clampedVisible
        offsetNanos = clampedOffset
    }

    private fun zoomTime(factor: Double, anchorNanos: Long) {
        val after = (visibleNanos / factor).toLong().coerceIn(Nanos.ofMillis(200), duration)
        val ratio = (anchorNanos - offsetNanos).toDouble() / visibleNanos
        setVisible(anchorNanos - (after * ratio).toLong(), after)
    }

    private fun fitAll(session: EditorSession, time: Boolean = true) {
        val project = session.project
        var first = Long.MAX_VALUE
        var last = Long.MIN_VALUE
        for (channel in visibleChannels) {
            val keys = keyCache[channel.id] ?: continue
            if (keys.isEmpty()) continue
            first = min(first, keys.first().timeNanos)
            last = max(last, keys.last().timeNanos)
        }
        if (first == Long.MAX_VALUE) {
            first = 0L
            last = duration
        }
        val span = maxOf(Nanos.ofSeconds(1), last - first)
        val margin = span / 12
        if (time) setVisible(first - margin, span + margin * 2)
        if (!normalized) {
            var lo = Double.MAX_VALUE
            var hi = -Double.MAX_VALUE
            for (channel in visibleChannels) {
                val range = ranges[channel.id] ?: continue
                lo = min(lo, range.lo)
                hi = max(hi, range.hi)
            }
            if (lo < hi) {
                valueMin = lo
                valueMax = hi
            }
        }
        computeRanges(project)
    }

    private fun fitSelection(session: EditorSession) {
        val selection = session.selection
        val times = (selection.keyframeTimes + selection.valueKeys.map { it.nanos }).sorted()
        if (times.isEmpty()) {
            fitAll(session)
            return
        }
        val span = maxOf(Nanos.ofSeconds(1), times.last() - times.first())
        setVisible(times.first() - span / 4, span + span / 2)
    }

    private fun xAt(nanos: Long): Float = originX + ((nanos - offsetNanos).toDouble() / visibleNanos * width).toFloat()

    private fun nanosAt(x: Float): Long = offsetNanos + ((x - originX) / width * visibleNanos).toLong()

    private val plotTop: Float get() = originY + RULER_HEIGHT + PLOT_PAD

    private val plotBottom: Float get() = originY + height - PLOT_PAD

    private fun yOf(channel: GraphChannel, value: Double): Float {
        val range = range(channel)
        val t = (value - range.lo) / (range.hi - range.lo)
        return plotBottom - (t * (plotBottom - plotTop)).toFloat()
    }

    private fun valueOf(channel: GraphChannel, y: Float): Double {
        val range = range(channel)
        val t = (plotBottom - y) / (plotBottom - plotTop)
        return range.lo + t * (range.hi - range.lo)
    }

    private fun range(channel: GraphChannel): Range =
        if (normalized || mode == Mode.SPEED) ranges[channel.id] ?: Range(0.0, 1.0) else Range(valueMin, valueMax)

    private fun collectChannels(session: EditorSession) {
        visibleChannels.clear()
        keyCache.clear()
        val project = session.project
        val selection = session.selection
        val seenSpeedGroups = HashSet<String>()
        for (channel in GraphChannel.ALL) {
            val keys = channel.keys(project)
            keyCache[channel.id] = keys
            if (keys.isEmpty()) continue
            if (solo != null && solo != channel.id && solo != channel.speedGroup) continue
            if (solo == null && channel.id in hidden) continue
            if (selectedOnly && keys.none { channel.isSelected(selection, it.timeNanos) }) continue
            if (mode == Mode.SPEED && !seenSpeedGroups.add(channel.speedGroup)) continue
            visibleChannels += channel
        }
    }

    private fun computeRanges(project: EditorProject) {
        ranges.clear()
        val step = maxOf(1L, (visibleNanos / (width / CURVE_STEP)).toLong())
        for (channel in visibleChannels) {
            var lo = Double.MAX_VALUE
            var hi = -Double.MAX_VALUE
            val keys = keyCache[channel.id] ?: continue
            val first = keys.first().timeNanos
            val last = keys.last().timeNanos
            if (mode == Mode.VALUE) {
                for (key in keys) {
                    lo = min(lo, key.value)
                    hi = max(hi, key.value)
                }
                var t = first
                while (t <= last) {
                    channel.valueAt(project, t)?.let {
                        lo = min(lo, it)
                        hi = max(hi, it)
                    }
                    t += maxOf(step, (last - first) / 400 + 1)
                }
            } else {
                lo = 0.0
                var t = first
                while (t <= last) {
                    channel.speedAt(project, t)?.let { hi = max(hi, it) }
                    t += maxOf(step, (last - first) / 400 + 1)
                }
                for (key in keys) {
                    val index = key.index
                    if (index < keys.size - 1) hi = max(hi, speedAtKey(project, channel, key, out = true))
                    if (index > 0) hi = max(hi, speedAtKey(project, channel, key, out = false))
                }
                if (hi <= 0.0) hi = 1.0
            }
            if (lo == Double.MAX_VALUE) {
                lo = 0.0
                hi = 1.0
            }
            if (hi - lo < 1e-6) {
                val pad = if (abs(hi) < 1e-6) 1.0 else abs(hi) * 0.1
                lo -= pad
                hi += pad
            }
            val margin = (hi - lo) * (if (mode == Mode.SPEED) 0.12 else 0.1)
            ranges[channel.id] = Range(if (mode == Mode.SPEED) 0.0 else lo - margin, hi + margin)
        }
    }

    private fun channelList(session: EditorSession) {
        val project = session.project
        val selection = session.selection
        val list = ImGui.getWindowDrawList()
        list.addRectFilled(listX, originY, originX, originY + height, EditorTheme.LANE_HEADER.u32)
        list.addLine(originX - 1f, originY, originX - 1f, originY + height, EditorTheme.BORDER.u32, 1f)
        var y = originY + EditorFonts.px(6f)
        var group = ""
        val rowHeight = EditorFonts.px(22f)
        ImGui.pushID("graph-channels")
        val selectedOnlyNote = selectedOnly
        for (channel in GraphChannel.ALL) {
            val keys = keyCache[channel.id] ?: emptyList()
            if (keys.isEmpty()) continue
            if (channel.group != group) {
                group = channel.group
                EditorFonts.with(EditorFonts.small) {
                    list.addText(
                        listX + EditorFonts.px(10f),
                        y + EditorFonts.px(3f),
                        EditorTheme.TEXT_DIM.u32,
                        group.uppercase()
                    )
                }
                y += rowHeight * 0.85f
            }
            if (y > originY + height - rowHeight) break
            val visible = channel in visibleChannels
            val enabled = channel.enabled(project)
            val hasSelected = keys.any { channel.isSelected(selection, it.timeNanos) }
            ImGui.setCursorScreenPos(listX, y)
            val pressed = ImGui.invisibleButton("row-${channel.id}", LIST_WIDTH - EditorFonts.px(30f), rowHeight)
            val rowHovered = ImGui.isItemHovered()
            if (rowHovered) list.addRectFilled(listX, y, originX - 1f, y + rowHeight, EditorTheme.CONTROL.u32(0.5f))
            if (rowHovered) hoveredChannel = channel
            val swatchX = listX + EditorFonts.px(12f)
            val swatchY = y + rowHeight / 2f
            list.addCircleFilled(
                swatchX,
                swatchY,
                EditorFonts.px(4f),
                channel.color.u32(if (visible) 1f else 0.35f),
                12
            )
            val textColor = when {
                !enabled -> EditorTheme.TEXT_DIM.u32
                visible -> EditorTheme.TEXT.u32
                else -> EditorTheme.TEXT_MUTED.u32
            }
            EditorFonts.with(EditorFonts.small) {
                list.addText(
                    swatchX + EditorFonts.px(12f),
                    y + (rowHeight - ImGui.getFontSize()) / 2f,
                    textColor,
                    channel.label
                )
                val count = keys.size.toString()
                val countWidth = Widgets.textWidth(count)
                list.addText(
                    originX - EditorFonts.px(30f) - countWidth,
                    y + (rowHeight - ImGui.getFontSize()) / 2f,
                    EditorTheme.TEXT_DIM.u32,
                    count
                )
            }
            if (hasSelected) list.addRectFilled(
                listX,
                y + EditorFonts.px(4f),
                listX + EditorFonts.px(2f),
                y + rowHeight - EditorFonts.px(4f),
                EditorTheme.SELECTION.u32
            )
            ImGui.setCursorScreenPos(originX - EditorFonts.px(24f), y + (rowHeight - EditorFonts.px(18f)) / 2f)
            Widgets.iconToggle(
                "eye-${channel.id}",
                if (visible) Icon.EYE else Icon.EYE_OFF,
                visible,
                EditorFonts.px(18f),
                "Show or hide this curve"
            )?.let {
                if (it) hidden -= channel.id else hidden += channel.id
                if (solo != null) solo = null
            }
            if (pressed) {
                if (ImGui.getIO().keyAlt) solo = if (solo == channel.id) null else channel.id
                else if (ImGui.getIO().keyCtrl) {
                    session.selection =
                        keys.fold(if (ImGui.getIO().keyShift) selection else Selection.NONE) { acc, key ->
                            channel.select(
                                acc,
                                key.timeNanos,
                                true
                            )
                        }
                } else {
                    if (channel.id in hidden) hidden -= channel.id else hidden += channel.id
                }
            }
            if (rowHovered) Widgets.hint("${channel.label}  ${keys.size} keyframes\nClick toggles the curve   Alt+click solos it   Ctrl+click selects its keyframes")
            y += rowHeight
        }
        if (selectedOnlyNote && visibleChannels.isEmpty()) {
            EditorFonts.with(EditorFonts.small) {
                list.addText(
                    listX + EditorFonts.px(10f),
                    y + EditorFonts.px(6f),
                    EditorTheme.TEXT_DIM.u32,
                    "Select keyframes to show curves"
                )
            }
        }
        ImGui.popID()
    }

    private fun toolbar(session: EditorSession) {
        EditorTheme.pushToolbarStyle()
        try {
            val rowY = ImGui.getCursorScreenPosY()
            val right = ImGui.getCursorScreenPosX() + ImGui.getContentRegionAvailX()
            val modes = Mode.entries
            Widgets.segmented(
                "graph-mode",
                modes.map { it.label },
                modes.indexOf(mode),
                EditorFonts.px(58f),
                listOf("Value graph: each channel's value over time", "Speed graph: how fast each channel moves")
            )
                ?.let {
                    mode = modes[it]
                    fitRequested = true
                }
            ImGui.sameLine(0f, EditorFonts.px(10f))
            Widgets.iconToggle("graph-norm", Icon.FIT, normalized, Toolbar.BUTTON, "Normalize: fit every curve to its own height")
                ?.let {
                    normalized = it
                    fitRequested = true
                }
            ImGui.sameLine()
            Widgets.iconToggle("graph-sel", Icon.KEYFRAME, selectedOnly, Toolbar.BUTTON, "Show only channels with selected keyframes")
                ?.let { selectedOnly = it }
            ImGui.sameLine()
            Widgets.iconToggle("graph-handles", Icon.PATH, showHandles, Toolbar.BUTTON, "Show tangent handles on selected keyframes")
                ?.let { showHandles = it }
            ImGui.sameLine()
            Widgets.iconToggle("graph-snap", Icon.MAGNET, snap, Toolbar.BUTTON, "Snap keyframes to frames while dragging  hold Shift to bypass")
                ?.let { snap = it }
            ImGui.sameLine()
            Widgets.iconToggle("graph-link", Icon.LINK, linked, Toolbar.BUTTON, "Scroll and zoom together with the Timeline")
                ?.let {
                    linked = it
                    if (!it) {
                        ownOffset = offsetNanos
                        ownVisible = visibleNanos
                    }
                }
            ImGui.sameLine(0f, EditorFonts.px(10f))
            if (Widgets.iconButton("graph-fit", Icon.FULLSCREEN, Toolbar.BUTTON, "Fit the selection, or everything  F")) fitSelection(session)
            val rightWidth = Widgets.lastWidth("graph-right")
            ImGui.sameLine()
            ImGui.setCursorScreenPos(maxOf(ImGui.getCursorScreenPosX(), right - rightWidth), rowY)
            Widgets.measured("graph-right") {
                val selection = session.selection
                val hasKeys = selection.keyframeTimes.isNotEmpty() || selection.valueKeys.isNotEmpty()
                val easing = currentEasing(session)
                if (!hasKeys) ImGui.beginDisabled()
                EasingWidgets.picker("graph-easing", easing ?: Easing.LINEAR, EditorFonts.px(140f))
                    ?.let { applyEasing(session, it) }
                if (!hasKeys) ImGui.endDisabled()
                if (ImGui.isItemHovered()) Widgets.hint("Easing of the selected keyframes  F9 easy ease, Shift+F9 in, Ctrl+Shift+F9 out")
                ImGui.sameLine(0f, EditorFonts.px(6f))
                extrapolationMenu(session)
            }
        } finally {
            EditorTheme.popToolbarStyle()
        }
    }

    private fun currentEasing(session: EditorSession): Easing? {
        val selection = session.selection
        val project = session.project
        selection.keyframeTimes.minOrNull()?.let { return project.camera.keyframeAt(it)?.easing }
        selection.valueKeys.minByOrNull { it.nanos }?.let { return project.valueTrack(it.lane).at(it.nanos)?.easing }
        return null
    }

    private fun applyEasing(session: EditorSession, easing: Easing) {
        val selection = session.selection
        val commands = ArrayList<EditorCommand>()
        if (selection.keyframeTimes.isNotEmpty()) commands += SetKeyframeEasing(selection.keyframeTimes, easing)
        if (selection.valueKeys.isNotEmpty()) commands += SetValueKeyframeEasing(selection.valueKeys, easing)
        if (commands.size == 1) session.execute(commands.first()) else if (commands.isNotEmpty()) session.execute(
            CompoundCommand("Set easing", commands)
        )
    }

    private fun extrapolationMenu(session: EditorSession) {
        val project = session.project
        val target = hoveredOrSelectedChannel(session)
        val label = when (target) {
            null -> "Extrapolation"
            is GraphChannel.Camera -> "Path  ${project.camera.preExtrapolation.label} / ${project.camera.postExtrapolation.label}"
            is GraphChannel.Value -> "${target.lane.label}  ${project.valueTrack(target.lane).preExtrapolation.label} / ${
                project.valueTrack(
                    target.lane
                ).postExtrapolation.label
            }"
        }
        if (Widgets.popupButton("graph-extrap", label, EditorFonts.px(172f), muted = target == null)) {
            if (target == null) Widgets.mutedText("Select a keyframe to pick a track")
            else {
                val lane = (target as? GraphChannel.Value)?.lane
                val pre =
                    if (lane == null) project.camera.preExtrapolation else project.valueTrack(lane).preExtrapolation
                val post =
                    if (lane == null) project.camera.postExtrapolation else project.valueTrack(lane).postExtrapolation
                Widgets.mutedText("Before the first keyframe")
                for (option in Extrapolation.entries) {
                    if (ImGui.menuItem(option.label, "", pre == option)) session.execute(
                        SetTrackExtrapolation(
                            lane,
                            option,
                            post
                        )
                    )
                    Widgets.tooltip(option.description)
                }
                ImGui.separator()
                Widgets.mutedText("After the last keyframe")
                for (option in Extrapolation.entries) {
                    if (ImGui.menuItem(option.label, "", post == option)) session.execute(
                        SetTrackExtrapolation(
                            lane,
                            pre,
                            option
                        )
                    )
                    Widgets.tooltip(option.description)
                }
            }
            Widgets.endPopup()
        }
        Widgets.tooltip("What the track does before its first and after its last keyframe")
    }

    private fun hoveredOrSelectedChannel(session: EditorSession): GraphChannel? {
        val selection = session.selection
        if (selection.keyframeTimes.isNotEmpty()) return visibleChannels.firstOrNull { it is GraphChannel.Camera }
            ?: GraphChannel.ALL.first()
        selection.valueKeys.firstOrNull()
            ?.let { key -> return GraphChannel.ALL.firstOrNull { it is GraphChannel.Value && it.lane == key.lane } }
        return hoveredChannel?.takeIf { it in visibleChannels }
    }

    private fun statusText(session: EditorSession): String {
        val selection = session.selection
        val count = selection.keyframeTimes.size + selection.valueKeys.size
        return when {
            count == 0 -> "Drag keys and handles   double-click a curve to add a key   wheel zooms   middle drag pans"
            count == 1 -> "1 keyframe   drag vertically for value, horizontally for time   Alt keeps the other handle"
            else -> "$count keyframes   drag the brackets to stretch time"
        }
    }

    private fun drawGrid(list: ImDrawList) {
        list.addRectFilled(originX, originY, originX + width, originY + height, EditorTheme.APP_BG.u32)
        val rows = 8
        for (row in 0..rows) {
            val y = plotTop + (plotBottom - plotTop) * row / rows
            list.addLine(
                originX,
                y,
                originX + width,
                y,
                EditorTheme.TEXT.u32(if (row == rows / 2) 0.08f else 0.04f),
                1f
            )
        }
        val session = context.session ?: return
        val inPoint = session.project.inPointNanos
        val outPoint = if (session.project.outPointNanos > 0L) session.project.outPointNanos else duration
        if (outPoint > inPoint) list.addRectFilled(
            xAt(inPoint),
            plotTop,
            xAt(outPoint),
            plotBottom,
            EditorTheme.WORK_AREA.u32
        )
    }

    private fun drawRuler(list: ImDrawList) {
        list.addRectFilled(originX, originY, originX + width, originY + RULER_HEIGHT, EditorTheme.RULER_BG.u32)
        val step = rulerStep()
        val minor = step / 5L
        var tick = (offsetNanos / minor) * minor
        val end = offsetNanos + visibleNanos
        while (tick <= end) {
            val x = xAt(tick)
            if (x >= originX && x <= originX + width) {
                val major = tick % step == 0L
                val tickHeight = if (major) 9f else 4f
                list.addLine(
                    x,
                    originY + RULER_HEIGHT - tickHeight,
                    x,
                    originY + RULER_HEIGHT,
                    if (major) EditorTheme.TEXT_MUTED.u32 else EditorTheme.TEXT_DIM.u32(0.6f),
                    1f
                )
                if (major) {
                    list.addText(
                        EditorFonts.small,
                        13,
                        x + 4f,
                        originY + 3f,
                        EditorTheme.TEXT_MUTED.u32,
                        rulerLabel(tick, step)
                    )
                    list.addLine(x, originY + RULER_HEIGHT, x, originY + height, EditorTheme.LANE_LINE.u32(0.18f), 1f)
                }
            }
            tick += minor
        }
        list.addLine(
            originX,
            originY + RULER_HEIGHT,
            originX + width,
            originY + RULER_HEIGHT,
            EditorTheme.BORDER.u32,
            1f
        )
    }

    private fun rulerStep(): Long {
        val target = (90f / width * visibleNanos).toLong()
        return RULER_STEPS.firstOrNull { it >= target } ?: RULER_STEPS.last()
    }

    private fun rulerLabel(nanos: Long, step: Long): String {
        val clock = TimeFormat.clock(nanos)
        return if (step < Nanos.PER_SECOND) clock else clock.substringBefore('.')
    }

    private fun drawAxis(list: ImDrawList) {
        val session = context.session ?: return
        val channel =
            hoveredOrSelectedChannel(session)?.takeIf { it in visibleChannels } ?: visibleChannels.firstOrNull()
            ?: return
        val range = range(channel)
        val rows = 8
        EditorFonts.with(EditorFonts.small) {
            for (row in 0..rows) {
                val y = plotTop + (plotBottom - plotTop) * row / rows
                val value = range.hi - (range.hi - range.lo) * row / rows
                val text = if (mode == Mode.SPEED) String.format(
                    "%.1f %s/s",
                    value,
                    channel.unit.ifEmpty { "u" }) else channel.format(value)
                list.addText(originX + EditorFonts.px(6f), y - ImGui.getFontSize() / 2f, channel.color.u32(0.75f), text)
            }
            val label = if (mode == Mode.SPEED) "${channel.speedGroupLabel()} speed" else channel.label
            list.addText(
                originX + width - Widgets.textWidth(label) - EditorFonts.px(10f),
                originY + RULER_HEIGHT + EditorFonts.px(6f),
                channel.color.u32,
                label
            )
        }
    }

    private fun GraphChannel.speedGroupLabel(): String = when (this) {
        is GraphChannel.Camera -> when (part) {
            GraphChannel.Camera.Part.POSITION -> "Position"
            GraphChannel.Camera.Part.ROTATION -> "Rotation"
            GraphChannel.Camera.Part.FOV -> "Field of view"
        }

        is GraphChannel.Value -> lane.label
    }

    private fun drawCurves(list: ImDrawList, session: EditorSession) {
        val project = session.project
        val selection = session.selection
        val anySelected = selection.keyframeTimes.isNotEmpty() || selection.valueKeys.isNotEmpty()
        for (channel in visibleChannels) {
            val keys = keyCache[channel.id] ?: continue
            val first = keys.first().timeNanos
            val last = keys.last().timeNanos
            val focused =
                !anySelected || keys.any { channel.isSelected(selection, it.timeNanos) } || channel === hoveredChannel
            val alpha = (if (channel.enabled(project)) 1f else 0.4f) * (if (focused) 1f else 0.45f)
            val color = channel.color.u32(alpha)
            val dim = channel.color.u32(alpha * 0.4f)
            var previousX = Float.NaN
            var previousY = Float.NaN
            var x = originX
            val endX = originX + width
            while (x <= endX) {
                val nanos = nanosAt(x)
                val value = if (mode == Mode.VALUE) channel.valueAt(project, nanos) else channel.speedAt(project, nanos)
                if (value == null) {
                    previousX = Float.NaN
                    x += CURVE_STEP
                    continue
                }
                val y = yOf(channel, value)
                if (!previousX.isNaN()) {
                    val inside = nanos in first..last
                    if (inside && focused && anySelected) list.addQuadFilled(
                        previousX, previousY, x, y, x, plotBottom, previousX, plotBottom, channel.color.u32(alpha * 0.06f)
                    )
                    list.addLine(previousX, previousY, x, y, if (inside) color else dim, if (focused) 2f else 1.5f)
                }
                previousX = x
                previousY = y
                x += CURVE_STEP
            }
        }
    }

    private fun drawKeys(list: ImDrawList, session: EditorSession) {
        val project = session.project
        val selection = session.selection
        val radius = KEY_RADIUS
        for (channel in visibleChannels) {
            val keys = keyCache[channel.id] ?: continue
            for (key in keys) {
                val x = xAt(key.timeNanos)
                if (x < originX - radius || x > originX + width + radius) continue
                val value = if (mode == Mode.VALUE) key.value else channel.speedAt(project, key.timeNanos) ?: 0.0
                val y = yOf(channel, value)
                val selected = channel.isSelected(selection, key.timeNanos)
                if (selected && showHandles) drawHandles(list, project, channel, key, x, y)
                val fill = if (selected) EditorTheme.TEXT.u32 else channel.color.u32
                when (key.mode) {
                    SegmentMode.HOLD -> list.addRectFilled(
                        x - radius * 0.8f,
                        y - radius * 0.8f,
                        x + radius * 0.8f,
                        y + radius * 0.8f,
                        fill,
                        1.5f
                    )

                    SegmentMode.LINEAR -> Icons.diamond(list, x, y, radius, fill, true)
                    else -> list.addCircleFilled(x, y, radius * 0.85f, fill, 14)
                }
                if (selected) list.addCircle(x, y, radius + 1.5f, EditorTheme.SELECTION.u32, 16, 1.5f)
                else list.addCircle(x, y, radius * 0.85f, EditorTheme.APP_BG.u32(0.8f), 14, 1f)
            }
        }
    }

    private fun handlePositions(
        project: EditorProject,
        channel: GraphChannel,
        key: GraphChannel.Key
    ): Pair<FloatArray?, FloatArray?> {
        val keys = keyCache[channel.id] ?: return null to null
        val x = xAt(key.timeNanos)
        val baseValue = if (mode == Mode.VALUE) key.value else null
        var out: FloatArray? = null
        var into: FloatArray? = null
        if (key.index < keys.size - 1) {
            val easing = key.easing
            if (easing.hasHandles) {
                val custom = easing.toCustom()
                val spanNanos = channel.segmentSpanNanos(project, key.index)
                val spanSeconds = spanNanos / Nanos.PER_SECOND.toDouble()
                val hx = xAt(key.timeNanos + (spanNanos * custom.x1).toLong())
                val hy = if (mode == Mode.VALUE) {
                    val slope = channel.spatialSlope(project, key.index, false) * easing.slopeIn() / spanSeconds
                    yOf(channel, baseValue!! + slope * custom.x1 * spanSeconds)
                } else yOf(channel, speedAtKey(project, channel, key, out = true))
                out = floatArrayOf(
                    hx,
                    hy,
                    x,
                    if (mode == Mode.VALUE) yOf(channel, key.value) else yOf(
                        channel,
                        speedAtKey(project, channel, key, out = true)
                    )
                )
            }
        }
        if (key.index > 0) {
            val previous = keys[key.index - 1]
            val easing = previous.easing
            if (easing.hasHandles) {
                val custom = easing.toCustom()
                val spanNanos = channel.segmentSpanNanos(project, key.index - 1)
                val spanSeconds = spanNanos / Nanos.PER_SECOND.toDouble()
                val influence = 1.0 - custom.x2
                val hx = xAt(key.timeNanos - (spanNanos * influence).toLong())
                val hy = if (mode == Mode.VALUE) {
                    val slope = channel.spatialSlope(project, key.index - 1, true) * easing.slopeOut() / spanSeconds
                    yOf(channel, baseValue!! - slope * influence * spanSeconds)
                } else yOf(channel, speedAtKey(project, channel, key, out = false))
                into = floatArrayOf(
                    hx,
                    hy,
                    x,
                    if (mode == Mode.VALUE) yOf(channel, key.value) else yOf(
                        channel,
                        speedAtKey(project, channel, key, out = false)
                    )
                )
            }
        }
        return out to into
    }

    private fun speedAtKey(project: EditorProject, channel: GraphChannel, key: GraphChannel.Key, out: Boolean): Double {
        val keys = keyCache[channel.id] ?: return 0.0
        return if (out) {
            if (key.index >= keys.size - 1) return 0.0
            val span = channel.segmentSpanNanos(project, key.index) / Nanos.PER_SECOND.toDouble()
            if (span <= 0.0) 0.0 else channel.spatialSpeed(project, key.index, false) * key.easing.slopeIn() / span
        } else {
            if (key.index <= 0) return 0.0
            val previous = keys[key.index - 1]
            val span = channel.segmentSpanNanos(project, key.index - 1) / Nanos.PER_SECOND.toDouble()
            if (span <= 0.0) 0.0 else channel.spatialSpeed(
                project,
                key.index - 1,
                true
            ) * previous.easing.slopeOut() / span
        }
    }

    private fun drawHandles(
        list: ImDrawList,
        project: EditorProject,
        channel: GraphChannel,
        key: GraphChannel.Key,
        x: Float,
        y: Float
    ) {
        val (out, into) = handlePositions(project, channel, key)
        val color = EditorTheme.PURPLE.u32
        val hit = dragHit
        for ((handle, isOut) in listOf(out to true, into to false)) {
            if (handle == null) continue
            val active =
                drag == Drag.HANDLE && hit != null && hit.channel === channel && hit.key.timeNanos == key.timeNanos && hit.handleOut == isOut
            list.addLine(handle[2], handle[3], handle[0], handle[1], EditorTheme.PURPLE.u32(0.7f), 1.5f)
            list.addCircleFilled(handle[0], handle[1], HANDLE_RADIUS, if (active) EditorTheme.TEXT.u32 else color, 12)
        }
    }

    private fun drawStretchBrackets(list: ImDrawList, session: EditorSession) {
        val selection = session.selection
        val times = (selection.keyframeTimes + selection.valueKeys.map { it.nanos })
        if (times.size < 2) return
        val first = times.min()
        val last = times.max()
        if (last <= first) return
        val x1 = xAt(first)
        val x2 = xAt(last)
        val top = originY + RULER_HEIGHT + EditorFonts.px(4f)
        val color = EditorTheme.SELECTION.u32(0.7f)
        list.addLine(x1, top, x1, plotBottom, color, 1f)
        list.addLine(x2, top, x2, plotBottom, color, 1f)
        val size = BRACKET
        list.addRectFilled(x1 - size, top, x1, top + size * 2f, EditorTheme.SELECTION.u32, 2f)
        list.addRectFilled(x2, top, x2 + size, top + size * 2f, EditorTheme.SELECTION.u32, 2f)
    }

    private fun drawPlayhead(list: ImDrawList, nanos: Long) {
        val x = xAt(nanos)
        if (x < originX || x > originX + width) return
        list.addLine(x, originY, x, originY + height, EditorTheme.PLAYHEAD.u32, 1.5f)
        list.addTriangleFilled(x - 6f, originY, x + 6f, originY, x, originY + 8f, EditorTheme.PLAYHEAD.u32)
    }

    private fun drawBox(list: ImDrawList) {
        val mouseX = ImGui.getMousePosX()
        val mouseY = ImGui.getMousePosY()
        list.addRectFilled(
            min(boxX, mouseX),
            min(boxY, mouseY),
            max(boxX, mouseX),
            max(boxY, mouseY),
            EditorTheme.SELECTION.u32(0.15f)
        )
        list.addRect(
            min(boxX, mouseX),
            min(boxY, mouseY),
            max(boxX, mouseX),
            max(boxY, mouseY),
            EditorTheme.SELECTION.u32(0.8f),
            0f,
            0,
            1f
        )
    }

    private fun hitTest(session: EditorSession, mouseX: Float, mouseY: Float): Hit? {
        val project = session.project
        val selection = session.selection
        if (showHandles) {
            for (channel in visibleChannels) {
                val keys = keyCache[channel.id] ?: continue
                for (key in keys) {
                    if (!channel.isSelected(selection, key.timeNanos)) continue
                    val (out, into) = handlePositions(project, channel, key)
                    if (out != null && abs(out[0] - mouseX) <= HANDLE_RADIUS * 1.6f && abs(out[1] - mouseY) <= HANDLE_RADIUS * 1.6f) return Hit(
                        channel,
                        key,
                        true
                    )
                    if (into != null && abs(into[0] - mouseX) <= HANDLE_RADIUS * 1.6f && abs(into[1] - mouseY) <= HANDLE_RADIUS * 1.6f) return Hit(
                        channel,
                        key,
                        false
                    )
                }
            }
        }
        var best: Hit? = null
        var bestDistance = KEY_RADIUS * 1.8f
        for (channel in visibleChannels) {
            val keys = keyCache[channel.id] ?: continue
            for (key in keys) {
                val x = xAt(key.timeNanos)
                if (abs(x - mouseX) > bestDistance) continue
                val value = if (mode == Mode.VALUE) key.value else channel.speedAt(project, key.timeNanos) ?: continue
                val y = yOf(channel, value)
                val distance = max(abs(x - mouseX), abs(y - mouseY))
                if (distance <= bestDistance) {
                    bestDistance = distance
                    best = Hit(channel, key)
                }
            }
        }
        return best
    }

    private fun curveAt(session: EditorSession, mouseX: Float, mouseY: Float): GraphChannel? {
        val project = session.project
        val nanos = nanosAt(mouseX)
        var best: GraphChannel? = null
        var bestDistance = EditorFonts.px(7f)
        for (channel in visibleChannels) {
            val value = (if (mode == Mode.VALUE) channel.valueAt(project, nanos) else channel.speedAt(project, nanos))
                ?: continue
            val distance = abs(yOf(channel, value) - mouseY)
            if (distance < bestDistance) {
                bestDistance = distance
                best = channel
            }
        }
        return best
    }

    private fun stretchBracketAt(session: EditorSession, mouseX: Float, mouseY: Float): Boolean? {
        val selection = session.selection
        val times = selection.keyframeTimes + selection.valueKeys.map { it.nanos }
        if (times.size < 2) return null
        val top = originY + RULER_HEIGHT + EditorFonts.px(4f)
        if (mouseY < top || mouseY > top + BRACKET * 2f + EditorFonts.px(4f)) return null
        val x1 = xAt(times.min())
        val x2 = xAt(times.max())
        if (mouseX >= x1 - BRACKET - 3f && mouseX <= x1 + 3f) return true
        if (mouseX >= x2 - 3f && mouseX <= x2 + BRACKET + 3f) return false
        return null
    }

    private fun handleInput(session: EditorSession, hovered: Boolean) {
        val io = ImGui.getIO()
        val mouseX = ImGui.getMousePosX()
        val mouseY = ImGui.getMousePosY()
        val replay = session.replay ?: return
        if (hovered && drag == Drag.NONE) {
            val wheel = io.mouseWheel
            if (wheel != 0f) {
                when {
                    io.keyCtrl && !normalized && mode == Mode.VALUE -> {
                        val anchor = valueMin + (valueMax - valueMin) * ((plotBottom - mouseY) / (plotBottom - plotTop))
                        val factor = if (wheel > 0f) 0.8 else 1.25
                        valueMin = anchor + (valueMin - anchor) * factor
                        valueMax = anchor + (valueMax - anchor) * factor
                    }

                    io.keyShift -> setVisible(offsetNanos - (visibleNanos * 0.1 * wheel).toLong(), visibleNanos)
                    else -> zoomTime(if (wheel > 0f) 1.25 else 0.8, nanosAt(mouseX))
                }
            }
            if (ImGui.isMouseClicked(ImGuiMouseButton.Middle) || (ImGui.isMouseClicked(ImGuiMouseButton.Left) && io.keyAlt && hitTest(
                    session,
                    mouseX,
                    mouseY
                ) == null)
            ) {
                drag = Drag.PAN
                dragStartX = mouseX
                dragStartY = mouseY
                dragAppliedNanos = offsetNanos
                return
            }
            if (ImGui.isMouseClicked(ImGuiMouseButton.Left)) beginLeftDrag(session, mouseX, mouseY)
            if (ImGui.isMouseDoubleClicked(ImGuiMouseButton.Left)) doubleClick(session, mouseX, mouseY)
        }
        when (drag) {
            Drag.NONE -> {}
            Drag.PAN -> {
                if (!ImGui.isMouseDown(ImGuiMouseButton.Middle) && !ImGui.isMouseDown(ImGuiMouseButton.Left)) {
                    drag = Drag.NONE
                    return
                }
                val deltaNanos = ((mouseX - dragStartX) / width * visibleNanos).toLong()
                setVisible(dragAppliedNanos - deltaNanos, visibleNanos)
                if (!normalized && mode == Mode.VALUE) {
                    val deltaValue = (mouseY - dragStartY) / (plotBottom - plotTop) * (valueMax - valueMin)
                    valueMin += deltaValue
                    valueMax += deltaValue
                    dragStartY = mouseY
                }
                ImGui.setMouseCursor(ImGuiMouseCursor.ResizeAll)
            }

            Drag.SCRUB -> {
                val target = nanosAt(mouseX).coerceIn(0L, duration)
                if (target != lastScrub) {
                    lastScrub = target
                    replay.seek(target)
                }
                if (!ImGui.isMouseDown(ImGuiMouseButton.Left)) {
                    drag = Drag.NONE
                    lastScrub = -1L
                }
            }

            Drag.BOX -> {
                if (!ImGui.isMouseDown(ImGuiMouseButton.Left)) {
                    boxSelect(session, mouseX, mouseY)
                    drag = Drag.NONE
                }
            }

            Drag.KEYS -> {
                if (!ImGui.isMouseDown(ImGuiMouseButton.Left)) {
                    drag = Drag.NONE
                    dragHit = null
                    return
                }
                dragKeys(session, mouseX, mouseY)
            }

            Drag.HANDLE -> {
                if (!ImGui.isMouseDown(ImGuiMouseButton.Left)) {
                    drag = Drag.NONE
                    dragHit = null
                    return
                }
                dragHandle(session, mouseX, mouseY)
            }

            Drag.STRETCH -> {
                if (!ImGui.isMouseDown(ImGuiMouseButton.Left)) {
                    drag = Drag.NONE
                    return
                }
                dragStretch(session, mouseX)
                ImGui.setMouseCursor(ImGuiMouseCursor.ResizeEW)
            }
        }
    }

    private fun beginLeftDrag(session: EditorSession, mouseX: Float, mouseY: Float) {
        val io = ImGui.getIO()
        if (mouseY < originY + RULER_HEIGHT) {
            drag = Drag.SCRUB
            lastScrub = -1L
            return
        }
        stretchBracketAt(session, mouseX, mouseY)?.let { left ->
            drag = Drag.STRETCH
            stretchLeft = left
            dragStartX = mouseX
            val times = session.selection.keyframeTimes + session.selection.valueKeys.map { it.nanos }
            stretchOriginalSpan = times.max() - times.min()
            return
        }
        val hit = hitTest(session, mouseX, mouseY)
        if (hit == null) {
            if (!io.keyShift && !io.keyCtrl) session.selection = Selection.NONE
            drag = Drag.BOX
            boxX = mouseX
            boxY = mouseY
            return
        }
        val channel = hit.channel
        if (hit.handleOut != null) {
            drag = Drag.HANDLE
            dragHit = hit
            return
        }
        val selected = channel.isSelected(session.selection, hit.key.timeNanos)
        session.selection = when {
            io.keyCtrl && selected -> channel.deselect(session.selection, hit.key.timeNanos)
            io.keyShift || io.keyCtrl -> channel.select(session.selection, hit.key.timeNanos, true)
            selected -> session.selection
            else -> channel.select(session.selection, hit.key.timeNanos, false)
        }
        if (!channel.isSelected(session.selection, hit.key.timeNanos)) return
        drag = Drag.KEYS
        dragHit = hit
        dragStartX = mouseX
        dragStartY = mouseY
        dragAppliedNanos = 0L
        dragAnchorNanos = hit.key.timeNanos
        dragAxis = 0
        dragStartValues.clear()
        val keys = keyCache[channel.id] ?: emptyList()
        for (key in keys) if (channel.isSelected(session.selection, key.timeNanos)) dragStartValues[key.timeNanos] =
            key.value
        context.inspect = null
    }

    private fun doubleClick(session: EditorSession, mouseX: Float, mouseY: Float) {
        if (mouseY < originY + RULER_HEIGHT) return
        val hit = hitTest(session, mouseX, mouseY)
        if (hit != null && hit.handleOut == null) {
            session.replay?.seek(hit.key.timeNanos)
            drag = Drag.NONE
            return
        }
        val channel = curveAt(session, mouseX, mouseY) ?: return
        val nanos = snapTime(nanosAt(mouseX))
        channel.insertKey(session, nanos)
        drag = Drag.NONE
    }

    private fun snapTime(nanos: Long): Long {
        if (!snap || ImGui.getIO().keyShift) return maxOf(0L, nanos)
        val frame = context.timeline.frameNanos()
        return maxOf(0L, Math.round(nanos.toDouble() / frame) * frame)
    }

    private fun dragKeys(session: EditorSession, mouseX: Float, mouseY: Float) {
        val hit = dragHit ?: return
        val io = ImGui.getIO()
        val channel = hit.channel
        val dx = mouseX - dragStartX
        val dy = mouseY - dragStartY
        if (dragAxis == 0 && (abs(dx) > 3f || abs(dy) > 3f)) dragAxis =
            if (io.keyShift) (if (abs(dx) >= abs(dy)) 1 else 2) else 3
        if (dragAxis == 0) return
        val selection = session.selection
        val commands = ArrayList<EditorCommand>()
        var nextSelection = selection
        if (dragAxis and 1 != 0) {
            val rawDelta = (dx / width * visibleNanos).toLong()
            val target = snapTime(dragAnchorNanos + rawDelta)
            var delta = target - (dragAnchorNanos + dragAppliedNanos)
            val minimum = (selection.keyframeTimes + selection.valueKeys.map { it.nanos }).minOrNull() ?: 0L
            if (minimum + delta < 0L) delta = -minimum
            if (selection.keyframeTimes.isNotEmpty()) commands += MoveKeyframes(selection.keyframeTimes, delta)
            if (selection.valueKeys.isNotEmpty()) commands += MoveValueKeyframes(selection.valueKeys, delta)
            if (delta != 0L) {
                dragAppliedNanos += delta
                nextSelection = Selection(
                    keyframeTimes = selection.keyframeTimes.map { maxOf(0L, it + delta) }.toSet(),
                    valueKeys = selection.valueKeys.map { ValueKey(it.lane, maxOf(0L, it.nanos + delta)) }.toSet(),
                )
                dragStartValues = HashMap(dragStartValues.mapKeys { (time, _) -> maxOf(0L, time + delta) })
            }
        }
        if (dragAxis and 2 != 0 && mode == Mode.VALUE) {
            val range = range(channel)
            val deltaValue = -dy / (plotBottom - plotTop) * (range.hi - range.lo)
            for ((time, start) in dragStartValues.entries.sortedBy { it.key }) {
                if (!channel.isSelected(nextSelection, time)) continue
                var target = start + deltaValue
                if (io.keyCtrl) target = Math.round(target * 10.0) / 10.0
                channel.valueCommand(session.project, time, target)?.let { commands += it }
            }
        }
        if (commands.isEmpty()) return
        session.execute(if (commands.size == 1) commands.first() else CompoundCommand("Move keyframes", commands))
        session.selection = nextSelection
        ImGui.setMouseCursor(if (dragAxis == 1) ImGuiMouseCursor.ResizeEW else if (dragAxis == 2) ImGuiMouseCursor.ResizeNS else ImGuiMouseCursor.ResizeAll)
    }

    private fun dragHandle(session: EditorSession, mouseX: Float, mouseY: Float) {
        val hit = dragHit ?: return
        val project = session.project
        val channel = hit.channel
        val keys = keyCache[channel.id] ?: return
        val key = keys.firstOrNull { it.timeNanos == hit.key.timeNanos } ?: return
        val isOut = hit.handleOut ?: return
        val io = ImGui.getIO()
        val mouseNanos = nanosAt(mouseX)
        val commands = ArrayList<EditorCommand>()
        if (isOut) {
            if (key.index >= keys.size - 1) return
            val spanNanos = channel.segmentSpanNanos(project, key.index)
            if (spanNanos <= 0L) return
            val spanSeconds = spanNanos / Nanos.PER_SECOND.toDouble()
            val x1 = ((mouseNanos - key.timeNanos).toDouble() / spanNanos).coerceIn(0.02, 1.0)
            val easing = key.easing.toCustom()
            val newY1: Double
            if (mode == Mode.VALUE) {
                val desired = (valueOf(channel, mouseY) - key.value) / (x1 * spanSeconds)
                val spatial = channel.spatialSlope(project, key.index, false)
                newY1 =
                    if (abs(spatial) > 1e-9) (desired * spanSeconds / spatial * x1).coerceIn(-3.0, 4.0) else easing.y1
            } else {
                val speed = valueOf(channel, mouseY).coerceAtLeast(0.0)
                val spatial = channel.spatialSpeed(project, key.index, false)
                newY1 = if (spatial > 1e-9) (speed * spanSeconds / spatial * x1).coerceIn(0.0, 4.0) else easing.y1
            }
            commands += easingCommand(channel, key.timeNanos, easing.withOut(x1, newY1))
            if (!io.keyAlt && key.index > 0) mirrorArrival(project, channel, keys, key, newY1 / x1, commands)
        } else {
            if (key.index <= 0) return
            val previous = keys[key.index - 1]
            val spanNanos = channel.segmentSpanNanos(project, key.index - 1)
            if (spanNanos <= 0L) return
            val spanSeconds = spanNanos / Nanos.PER_SECOND.toDouble()
            val influence = ((key.timeNanos - mouseNanos).toDouble() / spanNanos).coerceIn(0.02, 1.0)
            val x2 = 1.0 - influence
            val easing = previous.easing.toCustom()
            val newY2: Double
            if (mode == Mode.VALUE) {
                val desired = (key.value - valueOf(channel, mouseY)) / (influence * spanSeconds)
                val spatial = channel.spatialSlope(project, key.index - 1, true)
                newY2 = if (abs(spatial) > 1e-9) (1.0 - desired * spanSeconds / spatial * influence).coerceIn(
                    -3.0,
                    4.0
                ) else easing.y2
            } else {
                val speed = valueOf(channel, mouseY).coerceAtLeast(0.0)
                val spatial = channel.spatialSpeed(project, key.index - 1, true)
                newY2 = if (spatial > 1e-9) (1.0 - speed * spanSeconds / spatial * influence).coerceIn(
                    -3.0,
                    1.0
                ) else easing.y2
            }
            commands += easingCommand(channel, previous.timeNanos, easing.withIn(x2, newY2))
            if (!io.keyAlt && key.index < keys.size - 1) mirrorDeparture(
                project,
                channel,
                keys,
                key,
                (1.0 - newY2) / influence,
                commands
            )
        }
        session.execute(if (commands.size == 1) commands.first() else CompoundCommand("Adjust handles", commands))
        ImGui.setMouseCursor(ImGuiMouseCursor.Hand)
    }

    private fun mirrorArrival(
        project: EditorProject,
        channel: GraphChannel,
        keys: List<GraphChannel.Key>,
        key: GraphChannel.Key,
        easeSlopeOut: Double,
        commands: MutableList<EditorCommand>
    ) {
        val previous = keys[key.index - 1]
        val spanOut = channel.segmentSpanNanos(project, key.index) / Nanos.PER_SECOND.toDouble()
        val spanIn = channel.segmentSpanNanos(project, key.index - 1) / Nanos.PER_SECOND.toDouble()
        if (spanOut <= 0.0 || spanIn <= 0.0) return
        val easing = previous.easing.toCustom()
        val influence = 1.0 - easing.x2
        val rate = if (mode == Mode.VALUE) {
            val spatialOut = channel.spatialSlope(project, key.index, false)
            val spatialIn = channel.spatialSlope(project, key.index - 1, true)
            if (abs(spatialIn) < 1e-9) return
            spatialOut * easeSlopeOut / spanOut * spanIn / spatialIn
        } else {
            val spatialOut = channel.spatialSpeed(project, key.index, false)
            val spatialIn = channel.spatialSpeed(project, key.index - 1, true)
            if (spatialIn < 1e-9) return
            spatialOut * easeSlopeOut / spanOut * spanIn / spatialIn
        }
        val y2 = (1.0 - rate * influence).coerceIn(-3.0, 4.0)
        commands += easingCommand(channel, previous.timeNanos, easing.withIn(easing.x2, y2))
    }

    private fun mirrorDeparture(
        project: EditorProject,
        channel: GraphChannel,
        keys: List<GraphChannel.Key>,
        key: GraphChannel.Key,
        easeSlopeIn: Double,
        commands: MutableList<EditorCommand>
    ) {
        val spanOut = channel.segmentSpanNanos(project, key.index) / Nanos.PER_SECOND.toDouble()
        val spanIn = channel.segmentSpanNanos(project, key.index - 1) / Nanos.PER_SECOND.toDouble()
        if (spanOut <= 0.0 || spanIn <= 0.0) return
        val easing = key.easing.toCustom()
        val rate = if (mode == Mode.VALUE) {
            val spatialOut = channel.spatialSlope(project, key.index, false)
            val spatialIn = channel.spatialSlope(project, key.index - 1, true)
            if (abs(spatialOut) < 1e-9) return
            spatialIn * easeSlopeIn / spanIn * spanOut / spatialOut
        } else {
            val spatialOut = channel.spatialSpeed(project, key.index, false)
            val spatialIn = channel.spatialSpeed(project, key.index - 1, true)
            if (spatialOut < 1e-9) return
            spatialIn * easeSlopeIn / spanIn * spanOut / spatialOut
        }
        val y1 = (rate * easing.x1).coerceIn(-3.0, 4.0)
        commands += easingCommand(channel, key.timeNanos, easing.withOut(easing.x1, y1))
    }

    private fun easingCommand(channel: GraphChannel, nanos: Long, easing: Easing): EditorCommand = when (channel) {
        is GraphChannel.Camera -> SetKeyframeEasing(setOf(nanos), easing)
        is GraphChannel.Value -> SetValueKeyframeEasing(setOf(ValueKey(channel.lane, nanos)), easing)
    }

    private fun dragStretch(session: EditorSession, mouseX: Float) {
        val selection = session.selection
        val times = selection.keyframeTimes + selection.valueKeys.map { it.nanos }
        if (times.size < 2) {
            drag = Drag.NONE
            return
        }
        val first = times.min()
        val last = times.max()
        val pivot = if (stretchLeft) last else first
        val edge = if (stretchLeft) first else last
        val target = snapTime(nanosAt(mouseX))
        val currentSpan = (edge - pivot).toDouble()
        val desiredSpan = (target - pivot).toDouble()
        if (abs(currentSpan) < 1.0 || desiredSpan * currentSpan <= 0.0) return
        val factor = desiredSpan / currentSpan
        if (abs(factor - 1.0) < 1e-4) return
        val command = ScaleKeyframes(selection.keyframeTimes, selection.valueKeys, pivot, factor)
        session.execute(command)
        session.selection = Selection(keyframeTimes = command.resultTimes, valueKeys = command.resultValueKeys)
    }

    private fun boxSelect(session: EditorSession, mouseX: Float, mouseY: Float) {
        val project = session.project
        val x1 = min(boxX, mouseX)
        val x2 = max(boxX, mouseX)
        val y1 = min(boxY, mouseY)
        val y2 = max(boxY, mouseY)
        if (x2 - x1 < 3f && y2 - y1 < 3f) return
        val io = ImGui.getIO()
        var selection = if (io.keyShift || io.keyCtrl) session.selection else Selection.NONE
        for (channel in visibleChannels) {
            val keys = keyCache[channel.id] ?: continue
            for (key in keys) {
                val x = xAt(key.timeNanos)
                if (x < x1 || x > x2) continue
                val value = if (mode == Mode.VALUE) key.value else channel.speedAt(project, key.timeNanos) ?: continue
                val y = yOf(channel, value)
                if (y < y1 || y > y2) continue
                selection = channel.select(selection, key.timeNanos, true)
            }
        }
        session.selection = selection
        if (!selection.isEmpty) context.inspect = null
    }

    private fun hoverFeedback(session: EditorSession) {
        val mouseX = ImGui.getMousePosX()
        val mouseY = ImGui.getMousePosY()
        if (mouseY < originY + RULER_HEIGHT) {
            ImGui.setMouseCursor(ImGuiMouseCursor.ResizeEW)
            return
        }
        stretchBracketAt(session, mouseX, mouseY)?.let {
            ImGui.setMouseCursor(ImGuiMouseCursor.ResizeEW)
            Widgets.hint("Drag to stretch the selected keyframes in time")
            return
        }
        val hit = hitTest(session, mouseX, mouseY)
        if (hit != null) {
            ImGui.setMouseCursor(ImGuiMouseCursor.Hand)
            val channel = hit.channel
            if (hit.handleOut != null) {
                val easing =
                    if (hit.handleOut) hit.key.easing else keyCache[channel.id]?.getOrNull(hit.key.index - 1)?.easing
                Widgets.hint("${if (hit.handleOut) "Out" else "In"} handle   ${easing?.label ?: ""}\nDrag horizontally for influence, vertically for ${if (mode == Mode.VALUE) "slope" else "speed"}   Alt breaks the pair")
            } else {
                val value = if (mode == Mode.VALUE) channel.format(hit.key.value) else String.format(
                    "%.2f %s/s",
                    channel.speedAt(session.project, hit.key.timeNanos) ?: 0.0,
                    channel.unit.ifEmpty { "u" })
                Widgets.hint("${channel.label}   ${TimeFormat.clock(hit.key.timeNanos)}\n$value   ${hit.key.mode.label}   ${hit.key.easing.label}\nDrag to move   Shift constrains   Ctrl toggles selection   double-click jumps there")
            }
            hoveredChannel = channel
            return
        }
        val channel = curveAt(session, mouseX, mouseY)
        if (channel != null) {
            hoveredChannel = channel
            val nanos = nanosAt(mouseX)
            val value = if (mode == Mode.VALUE) channel.valueAt(session.project, nanos)
                ?.let { channel.format(it) } else channel.speedAt(session.project, nanos)
                ?.let { String.format("%.2f %s/s", it, channel.unit.ifEmpty { "u" }) }
            Widgets.hint("${channel.label}   ${TimeFormat.clock(nanos)}   ${value ?: ""}\nDouble-click to add a keyframe here")
        }
    }

    private fun keyboard(session: EditorSession, hovered: Boolean) {
        if (!(ImGui.isWindowFocused() || hovered) || ImGui.getIO().wantTextInput) return
        val io = ImGui.getIO()
        if (ImGui.isKeyPressed(ImGuiKey.Delete, false) || ImGui.isKeyPressed(
                ImGuiKey.Backspace,
                false
            )
        ) session.deleteSelection()
        if (io.keyCtrl && ImGui.isKeyPressed(ImGuiKey.A, false)) {
            var selection = Selection.NONE
            for (channel in visibleChannels) for (key in keyCache[channel.id] ?: emptyList()) selection =
                channel.select(selection, key.timeNanos, true)
            session.selection = selection
        }
    }

    fun frame(session: EditorSession): Boolean {
        if (!hovered) return false
        fitSelection(session)
        return true
    }

    private fun contextMenu(session: EditorSession) {
        val hit = contextHit
        val selection = session.selection
        if (hit != null && hit.handleOut == null) {
            val channel = hit.channel
            if (!channel.isSelected(selection, hit.key.timeNanos)) session.selection =
                channel.select(selection, hit.key.timeNanos, false)
            val current = session.selection
            Widgets.mutedText("${channel.label}  ${TimeFormat.clock(hit.key.timeNanos)}")
            ImGui.separator()
            if (ImGui.menuItem("Go to keyframe")) session.replay?.seek(hit.key.timeNanos)
            if (ImGui.beginMenu("Easing")) {
                EasingWidgets.menu(hit.key.easing)?.let { applyEasing(session, it) }
                ImGui.endMenu()
            }
            if (ImGui.beginMenu("Interpolation")) {
                for (option in SegmentMode.entries) {
                    if (ImGui.menuItem(option.label, "", hit.key.mode == option)) {
                        val commands = ArrayList<EditorCommand>()
                        if (current.keyframeTimes.isNotEmpty()) commands += SetKeyframeMode(
                            current.keyframeTimes,
                            option
                        )
                        if (current.valueKeys.isNotEmpty()) commands += SetValueKeyframeMode(current.valueKeys, option)
                        if (commands.size == 1) session.execute(commands.first()) else if (commands.isNotEmpty()) session.execute(
                            CompoundCommand("Set interpolation", commands)
                        )
                    }
                }
                ImGui.endMenu()
            }
            if (ImGui.menuItem("Easy ease", "F9")) session.execute(
                EaseKeyframes.easyEase(
                    current.keyframeTimes,
                    current.valueKeys
                )
            )
            if (ImGui.menuItem("Ease in", "Shift+F9")) session.execute(
                EaseKeyframes.easeIn(
                    current.keyframeTimes,
                    current.valueKeys
                )
            )
            if (ImGui.menuItem(
                    "Ease out",
                    "Ctrl+Shift+F9"
                )
            ) session.execute(EaseKeyframes.easeOut(current.keyframeTimes, current.valueKeys))
            if (ImGui.menuItem("Linear")) session.execute(
                EaseKeyframes.linear(
                    current.keyframeTimes,
                    current.valueKeys
                )
            )
            ImGui.separator()
            val count = current.keyframeTimes.size + current.valueKeys.size
            if (ImGui.menuItem(
                    if (count > 1) "Delete $count keyframes" else "Delete keyframe",
                    "Del"
                )
            ) session.deleteSelection()
            return
        }
        Widgets.mutedText(TimeFormat.clock(contextNanos))
        ImGui.separator()
        val channel = hoveredChannel?.takeIf { it in visibleChannels }
        if (channel != null && ImGui.menuItem("Add ${channel.label.lowercase()} keyframe here")) channel.insertKey(
            session,
            snapTime(contextNanos)
        )
        if (ImGui.menuItem("Fit everything")) fitAll(session)
        if (ImGui.menuItem("Fit selection", "F")) fitSelection(session)
        ImGui.separator()
        if (ImGui.menuItem("Value graph", "", mode == Mode.VALUE)) {
            mode = Mode.VALUE
            fitRequested = true
        }
        if (ImGui.menuItem("Speed graph", "", mode == Mode.SPEED)) {
            mode = Mode.SPEED
            fitRequested = true
        }
        if (ImGui.menuItem("Normalize curves", "", normalized)) {
            normalized = !normalized
            fitRequested = true
        }
        if (ImGui.menuItem("Show all channels")) {
            hidden.clear()
            solo = null
        }
    }

    private companion object {
        val LIST_WIDTH: Float get() = EditorFonts.px(178f)
        val RULER_HEIGHT: Float get() = EditorFonts.px(22f)
        val PLOT_PAD: Float get() = EditorFonts.px(14f)
        val KEY_RADIUS: Float get() = EditorFonts.px(5.5f)
        val HANDLE_RADIUS: Float get() = EditorFonts.px(4.5f)
        val BRACKET: Float get() = EditorFonts.px(6f)
        const val CURVE_STEP = 2f
        val RULER_STEPS = longArrayOf(
            Nanos.ofMillis(50), Nanos.ofMillis(100), Nanos.ofMillis(200), Nanos.ofMillis(500),
            Nanos.ofSeconds(1), Nanos.ofSeconds(2), Nanos.ofSeconds(5), Nanos.ofSeconds(10), Nanos.ofSeconds(15),
            Nanos.ofSeconds(30), Nanos.ofSeconds(60), Nanos.ofSeconds(120), Nanos.ofSeconds(300), Nanos.ofSeconds(600),
            Nanos.ofSeconds(1800),
        )
    }
}
