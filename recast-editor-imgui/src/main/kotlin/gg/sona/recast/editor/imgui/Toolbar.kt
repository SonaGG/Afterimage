package gg.sona.recast.editor.imgui

import gg.sona.recast.camera.CameraMode
import gg.sona.recast.core.time.Nanos
import gg.sona.recast.editor.EditorSession
import gg.sona.recast.replay.session.ReplaySession
import imgui.ImGui
import imgui.flag.ImGuiCol
import imgui.flag.ImGuiCond
import imgui.flag.ImGuiStyleVar
import imgui.flag.ImGuiWindowFlags
import kotlin.math.abs

class Toolbar(private val context: EditorContext, private val workspace: EditorWorkspace) {
    fun draw(top: Float, height: Float) {
        val viewport = ImGui.getMainViewport()
        ImGui.setNextWindowPos(viewport.workPosX, top, ImGuiCond.Always)
        ImGui.setNextWindowSize(viewport.workSizeX, height, ImGuiCond.Always)
        val flags =
            ImGuiWindowFlags.NoDecoration or ImGuiWindowFlags.NoDocking or ImGuiWindowFlags.NoSavedSettings or ImGuiWindowFlags.NoFocusOnAppearing or ImGuiWindowFlags.NoNav or ImGuiWindowFlags.NoBringToFrontOnFocus or ImGuiWindowFlags.NoScrollbar or ImGuiWindowFlags.NoScrollWithMouse
        ImGui.pushStyleVar(ImGuiStyleVar.WindowRounding, 0f)
        ImGui.pushStyleVar(ImGuiStyleVar.WindowBorderSize, 0f)
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, EditorFonts.px(10f), 0f)
        ImGui.pushStyleVar(ImGuiStyleVar.ItemSpacing, EditorFonts.px(4f), 0f)
        ImGui.pushStyleColor(ImGuiCol.WindowBg, EditorTheme.APP_BG.u32)
        val open = ImGui.begin("##toolbar", flags)
        ImGui.popStyleVar(4)
        ImGui.pushStyleVar(ImGuiStyleVar.ItemSpacing, EditorFonts.px(4f), 0f)
        try {
            if (open) {
                val list = ImGui.getWindowDrawList()
                list.addLine(
                    viewport.workPosX,
                    top + height - 1f,
                    viewport.workPosX + viewport.workSizeX,
                    top + height - 1f,
                    EditorTheme.SEPARATOR.u32,
                    1f
                )
                val session = context.session
                val replay = session?.replay
                val rowHeight = SEGMENT
                val rowTop = top + (height - rowHeight) / 2f
                val left = ImGui.getWindowPosX() + EditorFonts.px(10f)
                val right = ImGui.getWindowPosX() + viewport.workSizeX - EditorFonts.px(10f)
                ImGui.setCursorScreenPos(left, rowTop)
                val toolsRight = left + Widgets.measured("tools") { tools(session, rowTop, rowHeight) }
                if (session != null && replay != null) {
                    val gap = EditorFonts.px(16f)
                    val modesX = right - Widgets.lastWidth("modes")
                    val transportWidth = Widgets.lastWidth("transport")
                    val centred = ImGui.getWindowPosX() + (viewport.workSizeX - transportWidth) / 2f
                    val transportX = centred.coerceAtMost(modesX - gap - transportWidth).coerceAtLeast(toolsRight + gap)
                    ImGui.setCursorScreenPos(transportX, rowTop)
                    Widgets.measured("transport") { transport(session, replay, rowTop, rowHeight) }
                    ImGui.setCursorScreenPos(modesX, rowTop)
                    Widgets.measured("modes") { cameraModes(session, rowTop, rowHeight) }
                }
            }
            ImGui.end()
        } finally {
            ImGui.popStyleColor()
            ImGui.popStyleVar()
        }
    }

    private fun tools(session: EditorSession?, rowTop: Float, rowHeight: Float) {
        val button = BUTTON
        val tools = SceneTool.entries
        Widgets.segmentedIcons(
            "tools",
            tools.map { it.icon },
            tools.indexOf(context.tool),
            tools.map { "${it.label}  ${it.key}" },
            button
        )?.let { context.tool = tools[it] }
        ImGui.sameLine(0f, EditorFonts.px(10f))
        Widgets.segmentedIcons(
            "space",
            listOf(Icon.CUBE, Icon.GLOBE),
            if (context.localSpace) 0 else 1,
            listOf("Local axes  X", "World axes  X"),
            button
        )?.let { context.localSpace = it == 0 }
        ImGui.sameLine(0f, EditorFonts.px(10f))
        Widgets.centerInRow(rowTop, rowHeight, button)
        val settings = context.host.camera.settings
        Widgets.iconToggle("path", Icon.PATH, settings.showPath, button, "Camera path in the scene  H")
            ?.let { settings.showPath = it }
        ImGui.sameLine()
        Widgets.centerInRow(rowTop, rowHeight, button)
        Widgets.iconToggle(
            "gizmos",
            Icon.TOOL_MOVE,
            context.ui.sceneGizmos,
            button,
            "Scene gizmos: transform handles, ghost camera, entity boxes"
        )
            ?.let { context.ui.sceneGizmos = it }
        ImGui.sameLine()
        Widgets.centerInRow(rowTop, rowHeight, button)
        Widgets.iconToggle("pip", Icon.MONITOR, context.ui.cameraPreview, button, "Camera preview")
            ?.let { context.ui.cameraPreview = it }
        if (session != null) {
            ImGui.sameLine(0f, EditorFonts.px(10f))
            Widgets.centerInRow(rowTop, rowHeight, button)
            Widgets.iconToggle(
                "autokey",
                Icon.AUTO_KEY,
                session.autoKey,
                button,
                "Auto key: moving the camera on a paused frame writes a keyframe at the playhead",
                EditorTheme.RECORD.u32
            )?.let { workspace.toggleAutoKey() }
            ImGui.sameLine()
            Widgets.centerInRow(rowTop, rowHeight, button)
            Widgets.iconToggle(
                "graph",
                Icon.GRAPH,
                workspace.isPanelOpen("Graph Editor"),
                button,
                "Graph Editor  Ctrl+G"
            )
                ?.let { workspace.togglePanel("Graph Editor") }
        }
    }

    private fun transport(session: EditorSession, replay: ReplaySession, rowTop: Float, rowHeight: Float) {
        val button = BUTTON
        val play = PLAY
        val frameNanos = context.timeline.frameNanos()
        Widgets.centerInRow(rowTop, rowHeight, button)
        if (Widgets.iconButton("t-start", Icon.SKIP_START, button, "Jump to start  Home")) replay.seek(replay.startNanos)
        ImGui.sameLine()
        Widgets.centerInRow(rowTop, rowHeight, button)
        if (Widgets.iconButton(
                "t-prev",
                Icon.REWIND,
                button,
                if (session.project.camera.isEmpty) "Back 5 seconds" else "Previous keyframe  ,"
            )
        ) {
            val previous = session.project.camera.previousKeyframeTime(replay.positionNanos)
            if (previous != null) replay.seek(previous) else replay.seekRelative(-Nanos.ofSeconds(5))
        }
        ImGui.sameLine()
        Widgets.centerInRow(rowTop, rowHeight, button)
        if (Widgets.iconButton("t-back", Icon.STEP_BACK, button, "Previous frame  Left")) replay.seekRelative(-frameNanos)
        ImGui.sameLine()
        Widgets.centerInRow(rowTop, rowHeight, play)
        if (Widgets.iconButton(
                "t-play",
                if (replay.playing) Icon.PAUSE else Icon.PLAY,
                play,
                if (replay.playing) "Pause  Space" else "Play  Space",
                active = replay.playing,
                iconScale = 0.5f,
                rounded = play / 2f
            )
        ) replay.togglePlaying()
        ImGui.sameLine()
        Widgets.centerInRow(rowTop, rowHeight, button)
        if (Widgets.iconButton("t-fwd", Icon.STEP_FORWARD, button, "Next frame  Right")) replay.seekRelative(frameNanos)
        ImGui.sameLine()
        Widgets.centerInRow(rowTop, rowHeight, button)
        if (Widgets.iconButton(
                "t-next",
                Icon.FAST_FORWARD,
                button,
                if (session.project.camera.isEmpty) "Forward 5 seconds" else "Next keyframe  ."
            )
        ) {
            val next = session.project.camera.nextKeyframeTime(replay.positionNanos)
            if (next != null) replay.seek(next) else replay.seekRelative(Nanos.ofSeconds(5))
        }
        ImGui.sameLine()
        Widgets.centerInRow(rowTop, rowHeight, button)
        if (Widgets.iconButton("t-end", Icon.SKIP_END, button, "Jump to end  End")) replay.seek(replay.endNanos)
        ImGui.sameLine(0f, EditorFonts.px(14f))
        Widgets.centerInRow(rowTop, rowHeight, button)
        val timeText = TimeFormat.timecode(replay.positionNanos, context.timeline.renderFps)
        val timeWidth = Widgets.tabularWidth(timeText, EditorFonts.timecode)
        val timePad = EditorFonts.px(10f)
        val fx = ImGui.getCursorScreenPosX()
        val fy = ImGui.getCursorScreenPosY()
        ImGui.getWindowDrawList()
            .addRectFilled(fx, fy, fx + timeWidth + timePad * 2f, fy + button, EditorTheme.FIELD.u32, EditorFonts.px(5f))
        ImGui.setCursorScreenPos(fx + timePad, fy + (button - EditorFonts.timecode.fontSize) / 2f)
        Widgets.tabular(timeText, EditorFonts.timecode, EditorTheme.TIMECODE.u32)
        ImGui.setCursorScreenPos(fx, fy)
        ImGui.dummy(timeWidth + timePad * 2f, button)
        if (ImGui.isItemHovered()) Widgets.hint(
            "${TimeFormat.clock(replay.positionNanos)} of ${TimeFormat.clock(replay.durationNanos)}   tick ${
                TimeFormat.ticks(
                    replay.positionNanos
                )
            }"
        )
        ImGui.sameLine(0f, EditorFonts.px(8f))
        Widgets.centerInRow(rowTop, rowHeight, button)
        speed(session, replay, button)
    }

    private fun speed(session: EditorSession, replay: ReplaySession, button: Float) {
        val driven = session.speedAt(replay.positionNanos) != null
        val label = TimeFormat.speed(replay.speed)
        val width = EditorFonts.px(56f)
        val x = ImGui.getCursorScreenPosX()
        val y = ImGui.getCursorScreenPosY()
        val pressed = ImGui.invisibleButton("t-speed", width, button)
        val hovered = ImGui.isItemHovered()
        val list = ImGui.getWindowDrawList()
        if (hovered) list.addRectFilled(x, y, x + width, y + button, EditorTheme.CONTROL_HOVER.u32, EditorFonts.px(5f))
        else list.addRectFilled(x, y, x + width, y + button, EditorTheme.CONTROL.u32, EditorFonts.px(5f))
        val color = when {
            replay.speed < 0 -> EditorTheme.WARNING.u32
            driven -> EditorTheme.SUCCESS.u32
            else -> EditorTheme.TEXT.u32
        }
        EditorFonts.with(EditorFonts.smallMedium) {
            val textWidth = Widgets.textWidth(label)
            list.addText(
                x + (width - textWidth) / 2f - EditorFonts.px(4f),
                y + (button - ImGui.getFontSize()) / 2f,
                color,
                label
            )
        }
        Icons.draw(
            list,
            Icon.CHEVRON_DOWN,
            x + width - EditorFonts.px(14f),
            y + (button - EditorFonts.px(9f)) / 2f,
            EditorFonts.px(9f),
            EditorTheme.TEXT_DIM.u32
        )
        if (hovered) Widgets.hint(if (driven) "Speed is driven by the Speed track" else "Playback speed   J and L shuttle")
        if (pressed) ImGui.openPopup("t-speed-menu")
        if (Widgets.beginPopup("t-speed-menu")) {
            for (preset in SPEEDS) {
                if (ImGui.menuItem(
                        TimeFormat.speed(preset),
                        "",
                        abs(abs(replay.speed) - preset) < 1e-6
                    )
                ) replay.speed = if (replay.speed < 0) -preset else preset
            }
            ImGui.separator()
            if (ImGui.menuItem("Reverse", "J", replay.speed < 0)) replay.speed = -abs(replay.speed)
            if (ImGui.menuItem("Forward", "L", replay.speed > 0)) replay.speed = abs(replay.speed)
            Widgets.endPopup()
        }
    }

    private fun cameraModes(session: EditorSession, rowTop: Float, rowHeight: Float) {
        val control = context.host.camera
        val settings = control.settings
        val modes = CameraMode.entries
        val button = BUTTON
        Widgets.segmentedIcons("modes", MODE_ICONS, modes.indexOf(settings.mode), modes.map { it.label }, button)?.let {
            settings.mode = modes[it]
            control.apply()
        }
        val targetLabel = if (settings.mode == CameraMode.FREE) "" else workspace.targetName(settings)
        if (targetLabel.isNotEmpty()) {
            ImGui.sameLine(0f, EditorFonts.px(6f))
            Widgets.centerInRow(rowTop, rowHeight, button)
            val targetWidth = EditorFonts.with(EditorFonts.small) { Widgets.textWidth(targetLabel) } + EditorFonts.px(14f)
            val lx = ImGui.getCursorScreenPosX()
            val ly = ImGui.getCursorScreenPosY()
            val pressed = ImGui.invisibleButton("t-target", targetWidth, button)
            val list = ImGui.getWindowDrawList()
            if (ImGui.isItemHovered()) list.addRectFilled(
                lx,
                ly,
                lx + targetWidth,
                ly + button,
                EditorTheme.CONTROL_HOVER.u32,
                EditorFonts.px(5f)
            )
            EditorFonts.with(EditorFonts.small) {
                list.addText(
                    lx + EditorFonts.px(7f),
                    ly + (button - ImGui.getFontSize()) / 2f,
                    EditorTheme.TEXT_MUTED.u32,
                    targetLabel
                )
            }
            if (ImGui.isItemHovered()) Widgets.hint("Camera target. Click to pick another player or entity.")
            if (pressed) ImGui.openPopup("t-target-menu")
            if (Widgets.beginPopup("t-target-menu")) {
                workspace.targetMenu(session)
                Widgets.endPopup()
            }
        }
        val status = context.host.recording.status()
        if (status.recording) {
            ImGui.sameLine(0f, EditorFonts.px(10f))
            Widgets.centerInRow(rowTop, rowHeight, ImGui.getFrameHeight())
            Widgets.pill("REC ${TimeFormat.clock(status.elapsedNanos).substringBefore('.')}", EditorTheme.RECORD)
        }
        ImGui.sameLine(0f, EditorFonts.px(10f))
        Widgets.centerInRow(rowTop, rowHeight, button)
        if (Widgets.iconButton(
                "t-export",
                Icon.EXPORT,
                button,
                "Export  Ctrl+E starts one right away",
                active = workspace.isPanelOpen("Export")
            )
        ) workspace.togglePanel("Export")
        ImGui.sameLine()
        Widgets.centerInRow(rowTop, rowHeight, button)
        if (Widgets.iconButton(
                "t-settings",
                Icon.SETTINGS,
                button,
                "Settings",
                active = workspace.isPanelOpen("Settings")
            )
        ) workspace.togglePanel("Settings")
        ImGui.sameLine()
        Widgets.centerInRow(rowTop, rowHeight, button)
        if (Widgets.iconButton("t-full", Icon.FULLSCREEN, button, "Fullscreen scene  Tab")) workspace.fullscreen = true
    }

    companion object {
        val BUTTON: Float get() = EditorFonts.px(26f)
        val PLAY: Float get() = EditorFonts.px(34f)
        val SEGMENT: Float get() = BUTTON + EditorFonts.px(4f)
        val SPEEDS = doubleArrayOf(0.25, 0.5, 1.0, 2.0, 4.0, 8.0)
        val MODE_ICONS = listOf(Icon.CAMERA, Icon.EYE, Icon.ORBIT, Icon.FOLLOW, Icon.TARGET)
    }
}
