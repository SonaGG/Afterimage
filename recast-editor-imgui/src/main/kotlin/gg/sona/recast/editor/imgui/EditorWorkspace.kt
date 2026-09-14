package gg.sona.recast.editor.imgui

import gg.sona.recast.camera.CameraMode
import gg.sona.recast.camera.CameraPose
import gg.sona.recast.camera.CameraSettings
import gg.sona.recast.camera.Rotation
import gg.sona.recast.camera.track.SegmentMode
import gg.sona.recast.clip.recording.trim.RecordingTrimmer
import gg.sona.recast.clip.recording.trim.TrimJob
import gg.sona.recast.core.time.Nanos
import gg.sona.recast.editor.*
import gg.sona.recast.editor.commands.*
import gg.sona.recast.replay.state.shadow.EntityKind
import imgui.ImGui
import imgui.flag.*
import imgui.type.ImBoolean
import imgui.type.ImInt
import org.joml.Vector3d
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.*
import kotlin.math.abs
import imgui.internal.ImGui as DockBuilder

class EditorWorkspace(val context: EditorContext) {
    val panels: List<EditorPanel> = listOf(
        HierarchyPanel(context),
        InspectorPanel(context),
        CameraPanel(context),
        ExportPanel(context),
        VisualsPanel(context),
        RenderFilterPanel(context),
        KeybindsPanel(context),
        SettingsPanel(context),
        SequencePanel(context),
        ScoreboardPanel(context),
        StatsPanel(context),
        TimelinePanel(context),
        GraphEditorPanel(context),
        ClipsPanel(context),
        SearchPanel(context),
        InboxPanel(context),
    )

    var resetLayoutRequested: Boolean = false
    var fullscreen: Boolean = false
    var frameRect: ViewRect? = null
        private set
    private var layoutBuilt = false
    private var dockId = 0
    private var builtWidth = 0f
    private var builtHeight = 0f
    private val library = LibraryScreen(context)
    private val toolbar = Toolbar(context, this)
    private val tools = SceneTools(context)
    private val palette = CommandPalette()
    private val shortcutsOpen = ImBoolean(false)
    private val aboutOpen = ImBoolean(false)
    private val shortcutsDialog = Dialog("Keyboard Shortcuts", Icon.COMMAND, 720f, 560f)
    private var autosaveVersion = -1L
    private var recordingPath = false
    private var autoKeyLastPose: CameraPose? = null
    private var graphDockWaitFrames = 0
    private var autoKeyLastNanos = -1L
    private var lastRecordedNanos = -1L
    private var autosaveDueNanos = 0L
    private var sessionSeen: Any? = null
    private var popupEntity: EntityRef? = null
    private var rightPressX = 0f
    private var rightPressY = 0f
    private var focusDefaultsFrames = 0

    fun draw(frame: FrameContext) {
        val session = context.session
        if (session != null && sessionSeen !== session) {
            sessionSeen = session
            context.inspect = InspectTarget.Project
            context.selectedEntityId = null
            if (session.project.isSequence) panels.firstOrNull { it.title == "Sequence" }?.open?.set(true)
        }
        if (context.fullscreenRequested) {
            context.fullscreenRequested = false
            fullscreen = !fullscreen
        }
        loopPlayback()
        autosave(frame.nowNanos)
        recordPath()
        autoKey()
        if (session == null) {
            frameRect = null
            fullscreen = false
            recordingPath = false
            context.host.gizmos.clear()
            library.draw(frame, STATUS_HEIGHT)
            statusBar(frame)
            palette.draw(frame, paletteCommands())
            if (ImGui.getIO().keyCtrl && ImGui.isKeyPressed(ImGuiKey.P, false)) palette.toggle()
            return
        }
        if (fullscreen) {
            frameRect = null
            context.host.gizmos.clear()
            fullscreenOverlay()
            palette.draw(frame, paletteCommands())
            shortcuts()
            return
        }
        val viewport = ImGui.getMainViewport()
        dockId = dockHost()
        toolbar.draw(viewport.workPosY, TOOLBAR_HEIGHT)
        if (layoutBuilt && builtWidth > 0f && (frame.displayWidth / builtWidth > RELAYOUT_RATIO || builtWidth / frame.displayWidth > RELAYOUT_RATIO || frame.displayHeight / builtHeight > RELAYOUT_RATIO || builtHeight / frame.displayHeight > RELAYOUT_RATIO)) {
            resetLayoutRequested = true
        }
        if (!layoutBuilt || resetLayoutRequested) {
            buildLayout(dockId, frame)
            layoutBuilt = true
            resetLayoutRequested = false
            builtWidth = frame.displayWidth
            builtHeight = frame.displayHeight
            focusDefaultsFrames = 2
        }
        if (focusDefaultsFrames > 0) {
            focusDefaultsFrames--
            for (title in DEFAULT_TABS) ImGui.setWindowFocus(title)
        }
        migrateSearchDock()
        migrateGraphDock()
        updateFrame(frame)
        menuBar()
        context.openPanelRequest?.let { title ->
            context.openPanelRequest = null
            panels.firstOrNull { it.title == title }?.let { panel ->
                if (panel.dockArea == DockArea.FLOATING) closeDialogs()
                panel.open.set(true)
                if (panel.dockArea != DockArea.FLOATING) ImGui.setWindowFocus(title)
            }
        }
        for (panel in panels) {
            if (panel is GraphEditorPanel && !context.ui.graphDocked) continue
            panel.draw(frame)
        }
        frameRect?.let { tools.draw(it, frame) }
        sceneInteraction()
        if (context.ui.viewportOverlay) sceneOverlay()
        statusBar(frame)
        shortcutsWindow()
        palette.draw(frame, paletteCommands())
        shortcuts()
        updateHint()
    }

    fun isPanelOpen(title: String): Boolean = panels.firstOrNull { it.title == title }?.open?.get() == true

    val dialogOpen: Boolean get() = panels.any { it.dockArea == DockArea.FLOATING && it.open.get() } || shortcutsOpen.get() || aboutOpen.get()

    val consumesEscape: Boolean get() = dialogOpen || palette.open

    private fun closeDialogs() {
        for (panel in panels) if (panel.dockArea == DockArea.FLOATING) panel.open.set(false)
        shortcutsOpen.set(false)
        aboutOpen.set(false)
    }

    fun togglePanel(title: String) {
        val panel = panels.firstOrNull { it.title == title } ?: return
        if (panel.open.get()) panel.open.set(false) else context.openPanel(title)
    }

    private fun loopPlayback() {
        val session = context.session ?: return
        val replay = session.replay ?: return
        if (!context.loopPlayback || !replay.playing || replay.speed <= 0.0) return
        val inPoint = session.project.inPointNanos
        val outPoint = if (session.project.outPointNanos > 0L) session.project.outPointNanos else replay.durationNanos
        if (outPoint <= inPoint) return
        if (replay.positionNanos >= outPoint) replay.seek(inPoint)
    }

    private fun migrateSearchDock() {
        if (context.ui.searchDockedRight) return
        val inspector = panels.firstOrNull { it.title == "Inspector" } as? AbstractPanel ?: return
        if (inspector.dockId == 0) return
        DockBuilder.dockBuilderDockWindow("Search", inspector.dockId)
        context.ui.searchDockedRight = true
    }

    private fun migrateGraphDock() {
        if (context.ui.graphDocked) return
        val timeline = panels.firstOrNull { it.title == "Timeline" } as? AbstractPanel ?: return
        graphDockWaitFrames++
        if (timeline.dockId == 0 && graphDockWaitFrames < GRAPH_DOCK_WAIT_FRAMES) return
        if (timeline.dockId != 0) DockBuilder.dockBuilderDockWindow("Graph Editor", timeline.dockId)
        context.ui.graphDocked = true
        focusDefaultsFrames = 2
    }

    private fun autoKey() {
        val session = context.session
        val replay = session?.replay
        if (session == null || replay == null || !session.autoKey || recordingPath) {
            autoKeyLastPose = null
            return
        }
        val control = context.host.camera
        val pose = control.currentPose()
        val nanos = replay.positionNanos
        val eligible = !replay.playing && control.settings.mode == CameraMode.FREE && !control.pathActive
        if (!eligible || nanos != autoKeyLastNanos) {
            autoKeyLastPose = if (eligible) pose else null
            autoKeyLastNanos = nanos
            return
        }
        val last = autoKeyLastPose
        autoKeyLastPose = pose
        if (last == null) return
        val moved = last.position.distanceSquared(pose.position) > 1e-8 ||
                abs(Rotation.shortestDelta(last.rotation.yaw, pose.rotation.yaw)) > 1e-4 ||
                abs(last.rotation.pitch - pose.rotation.pitch) > 1e-4 ||
                abs(Rotation.shortestDelta(last.rotation.roll, pose.rotation.roll)) > 1e-4 ||
                abs(last.fov - pose.fov) > 1e-4
        if (!moved) return
        val existing = session.project.camera.keyframeAt(nanos)
        session.execute(
            SetCameraKeyframe(
                nanos,
                pose,
                existing?.easing ?: session.defaultEasing,
                existing?.mode ?: session.defaultKeyframeMode
            )
        )
        if (existing == null) {
            session.selection = Selection(keyframeTimes = setOf(nanos))
            val lane = session.project.lane(LaneKind.CAMERA)
            if (lane.muted) session.execute(SetLaneState(LaneKind.CAMERA, lane.copy(muted = false)))
        }
    }

    fun toggleAutoKey() {
        val session = context.session ?: return
        session.autoKey = !session.autoKey
        autoKeyLastPose = null
        context.status(if (session.autoKey) "Auto key on: moving the camera on a paused frame sets a keyframe" else "Auto key off")
    }

    private fun duplicateAsCut(session: EditorSession) {
        val project = session.project
        val store = ProjectStore(context.host.projectsDirectory)
        val existing = store.list().map { it.name }.toSet()
        var number = 2
        while ("${project.name} cut $number" in existing) number++
        val name = "${project.name} cut $number"
        val path = store.pathFor(name)
        runCatching {
            if (project.dirty) project.file?.let { ProjectCodec.save(project, it) }
            ProjectCodec.cut(project, name, path)
        }.onSuccess {
            context.toast("Created $name")
            context.host.later { if (!context.host.replay.openProject(path)) context.status("Saved $name; open it from the library") }
        }.onFailure { context.status("Could not create the cut: ${it.message}") }
    }

    private fun dockHost(): Int {
        val viewport = ImGui.getMainViewport()
        ImGui.setNextWindowPos(viewport.workPosX, viewport.workPosY + TOOLBAR_HEIGHT)
        ImGui.setNextWindowSize(viewport.workSizeX, viewport.workSizeY - STATUS_HEIGHT - TOOLBAR_HEIGHT)
        ImGui.setNextWindowViewport(viewport.id)
        ImGui.pushStyleVar(ImGuiStyleVar.WindowRounding, 0f)
        ImGui.pushStyleVar(ImGuiStyleVar.WindowBorderSize, 0f)
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 0f, 0f)
        val flags =
            ImGuiWindowFlags.NoDocking or ImGuiWindowFlags.NoTitleBar or ImGuiWindowFlags.NoCollapse or ImGuiWindowFlags.NoResize or ImGuiWindowFlags.NoMove or
                    ImGuiWindowFlags.NoBringToFrontOnFocus or ImGuiWindowFlags.NoNavFocus or ImGuiWindowFlags.NoBackground or ImGuiWindowFlags.NoSavedSettings
        ImGui.begin("##recast-dock-host", flags)
        ImGui.popStyleVar(3)
        val id = ImGui.getID("recast-dockspace")
        ImGui.dockSpace(id, 0f, 0f, ImGuiDockNodeFlags.PassthruCentralNode)
        ImGui.end()
        return id
    }

    private fun buildLayout(dockId: Int, frame: FrameContext) {
        val existing = DockBuilder.dockBuilderGetNode(dockId)
        if (existing != null && !resetLayoutRequested && existing.isSplitNode) return
        DockBuilder.dockBuilderRemoveNodeDockedWindows(dockId, true)
        DockBuilder.dockBuilderRemoveNodeChildNodes(dockId)
        val height = frame.displayHeight - STATUS_HEIGHT - TOOLBAR_HEIGHT
        DockBuilder.dockBuilderSetNodeSize(dockId, frame.displayWidth, height)
        val right = ImInt()
        val rest = ImInt()
        val inspectorShare = (EditorFonts.px(330f) / frame.displayWidth).coerceIn(0.18f, 0.3f)
        DockBuilder.dockBuilderSplitNode(dockId, ImGuiDir.Right, inspectorShare, right, rest)
        val bottom = ImInt()
        val upper = ImInt()
        DockBuilder.dockBuilderSplitNode(rest.get(), ImGuiDir.Down, 0.3f, bottom, upper)
        val left = ImInt()
        val center = ImInt()
        val hierarchyShare =
            (EditorFonts.px(230f) / (frame.displayWidth * (1f - inspectorShare))).coerceIn(0.14f, 0.26f)
        DockBuilder.dockBuilderSplitNode(upper.get(), ImGuiDir.Left, hierarchyShare, left, center)
        for (panel in panels) {
            val target = when (panel.dockArea) {
                DockArea.LEFT -> left.get()
                DockArea.RIGHT -> right.get()
                DockArea.BOTTOM -> bottom.get()
                DockArea.FLOATING -> continue
            }
            DockBuilder.dockBuilderDockWindow(panel.title, target)
        }
        DockBuilder.dockBuilderFinish(dockId)
        context.ui.graphDocked = true
    }

    private fun updateFrame(frame: FrameContext) {
        val central = DockBuilder.dockBuilderGetCentralNode(dockId)
        if (central == null || frame.displayHeight <= 0f) {
            frameRect = null
            return
        }
        val x = central.posX
        val y = central.posY
        val width = central.sizeX
        val height = central.sizeY
        val aspect = frame.displayWidth / frame.displayHeight
        var fitWidth = width
        var fitHeight = width / aspect
        if (fitHeight > height) {
            fitHeight = height
            fitWidth = height * aspect
        }
        val fitX = x + (width - fitWidth) / 2f
        val fitY = y + (height - fitHeight) / 2f
        frameRect = ViewRect(fitX, fitY, fitWidth, fitHeight)
        val draw = ImGui.getBackgroundDrawList()
        val bar = EditorTheme.PANEL_SUNKEN.u32
        if (fitY - y > 0.5f) {
            draw.addRectFilled(x, y, x + width, fitY, bar)
            draw.addRectFilled(x, fitY + fitHeight, x + width, y + height, bar)
        }
        if (fitX - x > 0.5f) {
            draw.addRectFilled(x, y, fitX, y + height, bar)
            draw.addRectFilled(fitX + fitWidth, y, x + width, y + height, bar)
        }
        val visuals = context.visuals
        if (visuals.centerGuide) {
            draw.addLine(
                fitX + fitWidth / 2f,
                fitY,
                fitX + fitWidth / 2f,
                fitY + fitHeight,
                EditorTheme.TEXT.u32(0.22f),
                1f
            )
            draw.addLine(
                fitX,
                fitY + fitHeight / 2f,
                fitX + fitWidth,
                fitY + fitHeight / 2f,
                EditorTheme.TEXT.u32(0.22f),
                1f
            )
        }
        if (visuals.thirdsGuide) {
            for (i in 1..2) {
                draw.addLine(
                    fitX + fitWidth * i / 3f,
                    fitY,
                    fitX + fitWidth * i / 3f,
                    fitY + fitHeight,
                    EditorTheme.TEXT.u32(0.18f),
                    1f
                )
                draw.addLine(
                    fitX,
                    fitY + fitHeight * i / 3f,
                    fitX + fitWidth,
                    fitY + fitHeight * i / 3f,
                    EditorTheme.TEXT.u32(0.18f),
                    1f
                )
            }
        }
    }

    private fun sceneInteraction() {
        val rect = frameRect ?: return
        val io = ImGui.getIO()
        val mouseX = ImGui.getMousePosX()
        val mouseY = ImGui.getMousePosY()
        val inside = !io.wantCaptureMouse && rect.contains(mouseX, mouseY)
        val replaying = context.replay != null
        val pick = tools.hoveredEntity
        if (inside && replaying && !tools.consumedClick && !io.keyAlt && ImGui.isMouseClicked(ImGuiMouseButton.Left)) {
            if (pick != null) {
                context.selectEntity(pick.entityId, pick.name, pick.isPlayer, pick.isRecorder, pick.uuid)
                if (ImGui.isMouseDoubleClicked(ImGuiMouseButton.Left)) context.host.camera.frame(
                    pick.x,
                    pick.y + 1.0,
                    pick.z,
                    2.0
                )
            } else if (!context.host.camera.gestureActive && context.selectedEntityId != null) {
                context.selectEntity(null)
            }
        }
        if (inside && ImGui.isMouseClicked(ImGuiMouseButton.Right)) {
            rightPressX = mouseX
            rightPressY = mouseY
        }
        if (inside && replaying && ImGui.isMouseReleased(ImGuiMouseButton.Right) && abs(mouseX - rightPressX) < 4f && abs(
                mouseY - rightPressY
            ) < 4f && context.host.camera.recentGestureTravel() < 4.0
        ) {
            val target =
                pick ?: context.host.pickEntity((mouseX - rect.x) / rect.width, (mouseY - rect.y) / rect.height)
            if (target != null) {
                popupEntity = EntityRef(
                    target.entityId,
                    target.name,
                    target.isPlayer,
                    target.isRecorder,
                    target.uuid,
                    target.x,
                    target.y,
                    target.z
                )
                ImGui.openPopup("scene-entity")
            }
        }
        if (ImGui.beginPopup("scene-entity")) {
            popupEntity?.let { EntityActions.menu(context, it) }
            ImGui.endPopup()
        } else {
            popupEntity = null
        }
    }

    private fun fullscreenOverlay() {
        val viewport = ImGui.getMainViewport()
        val replay = context.replay
        ImGui.setNextWindowPos(
            viewport.workPosX + EditorFonts.px(12f),
            viewport.workPosY + EditorFonts.px(12f),
            ImGuiCond.Always
        )
        ImGui.setNextWindowBgAlpha(0.6f)
        ImGui.pushStyleVar(ImGuiStyleVar.WindowRounding, EditorFonts.px(8f))
        val flags =
            ImGuiWindowFlags.NoDecoration or ImGuiWindowFlags.NoDocking or ImGuiWindowFlags.AlwaysAutoResize or ImGuiWindowFlags.NoSavedSettings or ImGuiWindowFlags.NoFocusOnAppearing or ImGuiWindowFlags.NoNav
        if (ImGui.begin("##fullscreen-overlay", flags)) {
            if (Widgets.iconButton(
                    "exit-fullscreen",
                    Icon.FULLSCREEN,
                    EditorFonts.px(24f),
                    "Exit fullscreen  Tab"
                )
            ) fullscreen = false
            if (replay != null) {
                ImGui.sameLine()
                ImGui.setCursorPosY(ImGui.getCursorPosY() + EditorFonts.px(4f))
                Widgets.tabular(
                    TimeFormat.timecode(replay.positionNanos, context.timeline.renderFps),
                    EditorFonts.timecode,
                    EditorTheme.TIMECODE.u32
                )
                ImGui.sameLine()
                ImGui.alignTextToFramePadding()
                Widgets.smallText(if (replay.playing) TimeFormat.speed(replay.speed) else "Paused")
            }
        }
        ImGui.end()
        ImGui.popStyleVar()
    }

    private fun sceneOverlay() {
        val rect = frameRect ?: return
        val session = context.session
        val replay = session?.replay ?: return
        val control = context.host.camera
        val flags =
            ImGuiWindowFlags.NoDecoration or ImGuiWindowFlags.NoDocking or ImGuiWindowFlags.AlwaysAutoResize or ImGuiWindowFlags.NoSavedSettings or ImGuiWindowFlags.NoFocusOnAppearing or ImGuiWindowFlags.NoNav or ImGuiWindowFlags.NoInputs or ImGuiWindowFlags.NoBackground
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 0f, 0f)
        ImGui.pushStyleVar(ImGuiStyleVar.ItemSpacing, EditorFonts.px(6f), 0f)
        try {
            ImGui.setNextWindowPos(rect.x + EditorFonts.px(10f), rect.y + EditorFonts.px(10f), ImGuiCond.Always)
            if (ImGui.begin("##scene-overlay", flags)) {
                val settings = control.settings
                when {
                    recordingPath -> Widgets.solidPill("Recording flight", EditorTheme.RECORD)
                    control.pathActive -> Widgets.solidPill("Camera path", EditorTheme.ACCENT)
                    control.pathSuspended -> Widgets.solidPill("Path paused", EditorTheme.WARNING)
                    else -> Widgets.pill(modeLabel(settings), EditorTheme.TEXT_MUTED, EditorTheme.TEXT.u32)
                }
                if (context.visuals.rtcOverlay) {
                    ImGui.sameLine()
                    val wall = Instant.ofEpochMilli(replay.header.startEpochMillis + replay.positionNanos / 1_000_000L)
                        .atZone(ZoneId.systemDefault())
                    Widgets.pill(wall.format(RTC_FORMAT), EditorTheme.TEXT_MUTED, EditorTheme.TEXT.u32)
                }
            }
            ImGui.end()
        } finally {
            ImGui.popStyleVar(2)
        }
    }

    private fun modeLabel(settings: CameraSettings): String {
        val target = targetName(settings)
        return when (settings.mode) {
            CameraMode.FREE -> "Free camera"
            CameraMode.FIRST_PERSON -> "First person  $target"
            CameraMode.ORBIT -> "Orbit  $target"
            CameraMode.FOLLOW -> "Follow  $target"
            CameraMode.CHASE -> "Chase  $target"
        }
    }

    fun targetName(settings: CameraSettings): String {
        val shadow = context.replay?.shadow ?: return "?"
        if (settings.targetsRecorder()) return shadow.localPlayer.name ?: "Recorder"
        val entity = shadow.entities[settings.targetEntityId] ?: return "#${settings.targetEntityId}"
        return entity.uuid?.let { shadow.players.profile(it)?.name } ?: "#${entity.id}"
    }

    fun targetMenu(session: EditorSession) {
        val control = context.host.camera
        val settings = control.settings
        val shadow = session.replay?.shadow ?: return
        val recorderName = shadow.localPlayer.name ?: "Recorder"
        if (ImGui.menuItem(recorderName, "", settings.targetsRecorder())) {
            settings.targetEntityId = CameraSettings.TARGET_RECORDER
            control.apply()
        }
        val players = shadow.entities.values().filter { it.kind == EntityKind.PLAYER }
            .mapNotNull { entity -> entity.uuid?.let { shadow.players.profile(it)?.name }?.let { it to entity.id } }
            .sortedBy { it.first.lowercase() }
        if (players.isNotEmpty()) ImGui.separator()
        for ((name, id) in players) {
            if (ImGui.menuItem(name, "", settings.targetEntityId == id)) {
                settings.targetEntityId = id
                control.apply()
            }
        }
    }

    private fun menuBar() {
        ImGui.pushStyleVar(ImGuiStyleVar.FramePadding, EditorFonts.px(8f), EditorFonts.px(5f))
        val open = ImGui.beginMainMenuBar()
        ImGui.popStyleVar()
        if (!open) return
        val session = context.session
        val replay = session?.replay
        if (ImGui.beginMenu("File")) {
            if (ImGui.menuItem("Save Project", "Ctrl+S", false, session != null)) saveProject()
            if (ImGui.menuItem("Export Video", "Ctrl+E", false, session != null)) context.openPanel("Export")
            if (ImGui.menuItem(
                    "Screenshot",
                    "F2",
                    false,
                    session != null && context.exports != null
                )
            ) exportPanel()?.screenshotNow()
            ImGui.separator()
            if (ImGui.menuItem(
                    "Save Trimmed Copy",
                    "",
                    false,
                    session != null && context.exports != null
                )
            ) trimRecording()
            if (ImGui.menuItem(
                    "Render POV Proxy",
                    "",
                    false,
                    session != null && context.exports != null
                )
            ) exportPanel()?.renderProxy()
            if (ImGui.menuItem("Add Gameplay to Project", "", false, session != null)) context.openPanel("Sequence")
            if (ImGui.menuItem("Duplicate as Cut", "", false, session != null)) session?.let { duplicateAsCut(it) }
            Widgets.tooltip("A second project on the same gameplay: change cameras and clips here without touching the original")
            if (ImGui.menuItem("Trim Segment to In and Out", "", false, session != null)) trimSegmentToInOut()
            ImGui.separator()
            if (ImGui.menuItem(
                    "Close Replay",
                    "",
                    false,
                    session != null
                )
            ) context.host.later { context.host.replay.close() }
            ImGui.endMenu()
        }
        if (ImGui.beginMenu("Edit")) {
            if (ImGui.menuItem(
                    "Undo ${session?.commands?.undoLabel ?: ""}".trim(),
                    "Ctrl+Z",
                    false,
                    session?.commands?.canUndo == true
                )
            ) session?.let { undo(it) }
            if (ImGui.menuItem(
                    "Redo ${session?.commands?.redoLabel ?: ""}".trim(),
                    "Ctrl+Y",
                    false,
                    session?.commands?.canRedo == true
                )
            ) session?.let { redo(it) }
            if (session != null && ImGui.beginMenu("History", session.commands.canUndo)) {
                val history = session.commands.history
                val shown = history.takeLast(15)
                for ((offset, command) in shown.asReversed().withIndex()) {
                    if (ImGui.menuItem(
                            command.label,
                            if (offset == 0) "latest" else ""
                        )
                    ) repeat(offset + 1) { session.commands.undo() }
                }
                ImGui.endMenu()
            }
            ImGui.separator()
            if (ImGui.menuItem(
                    "Cut",
                    "Ctrl+X",
                    false,
                    session?.selection?.isEmpty == false
                )
            ) session?.let { copySelection(it); it.deleteSelection() }
            if (ImGui.menuItem(
                    "Copy",
                    "Ctrl+C",
                    false,
                    session?.selection?.isEmpty == false
                )
            ) session?.let { copySelection(it) }
            if (ImGui.menuItem(
                    "Paste",
                    "Ctrl+V",
                    false,
                    context.clipboard != null && replay != null
                )
            ) context.clipboard?.let { clip -> session?.let { clip.paste(it, replay!!.positionNanos) } }
            if (ImGui.menuItem(
                    "Paste Relative to View",
                    "Ctrl+Shift+V",
                    false,
                    context.clipboard != null && replay != null
                )
            ) context.clipboard?.let { clip ->
                session?.let { clip.paste(it, replay!!.positionNanos, context.host.camera.currentPose()) }
            }
            if (ImGui.menuItem(
                    "Duplicate",
                    "Ctrl+D",
                    false,
                    session?.selection?.keyframeTimes?.size == 1
                )
            ) session?.let { duplicateSelectedKeyframe(it) }
            if (ImGui.menuItem("Delete", "Del", false, session?.selection?.isEmpty == false)) session?.deleteSelection()
            ImGui.separator()
            if (ImGui.menuItem("Select All Keyframes", "Ctrl+A", false, session != null)) session?.let {
                it.selection = Selection(keyframeTimes = it.project.camera.keyframeTimes().toSet())
            }
            if (ImGui.menuItem("Deselect", "Esc", false, session != null)) {
                session?.selection = Selection.NONE
                context.selectEntity(null)
            }
            ImGui.separator()
            if (ImGui.menuItem("Command Palette", "Ctrl+P")) palette.show()
            ImGui.endMenu()
        }
        if (ImGui.beginMenu("Playback")) {
            if (ImGui.menuItem("Play or Pause", "Space", false, replay != null)) replay?.togglePlaying()
            if (ImGui.menuItem("Play from In Point", "Shift+Space", false, replay != null)) replay?.let {
                it.seek(
                    session.project.inPointNanos
                ); it.play()
            }
            if (ImGui.menuItem("Shuttle Reverse", "J", false, replay != null)) shuttle(-1)
            if (ImGui.menuItem("Shuttle Forward", "L", false, replay != null)) shuttle(1)
            ImGui.separator()
            if (ImGui.menuItem(
                    "Step Frame Back",
                    "Left",
                    false,
                    replay != null
                )
            ) replay?.seekRelative(-context.timeline.frameNanos())
            if (ImGui.menuItem(
                    "Step Frame Forward",
                    "Right",
                    false,
                    replay != null
                )
            ) replay?.seekRelative(context.timeline.frameNanos())
            if (ImGui.menuItem("Step Tick Back", "Shift+Left", false, replay != null)) replay?.stepTicks(-1)
            if (ImGui.menuItem("Step Tick Forward", "Shift+Right", false, replay != null)) replay?.stepTicks(1)
            if (ImGui.menuItem("Jump to Start", "Home", false, replay != null)) replay?.seek(0L)
            if (ImGui.menuItem("Jump to End", "End", false, replay != null)) replay?.seek(replay.endNanos)
            ImGui.separator()
            if (ImGui.menuItem(
                    "Set In Point",
                    "I",
                    false,
                    replay != null
                )
            ) replay?.let {
                session.execute(
                    SetInOutPoints(
                        it.positionNanos,
                        maxOf(it.positionNanos, session.project.outPointNanos)
                    )
                )
            }
            if (ImGui.menuItem("Set Out Point", "O", false, replay != null)) replay?.let {
                session.execute(
                    SetInOutPoints(minOf(session.project.inPointNanos, it.positionNanos), it.positionNanos)
                )
            }
            if (ImGui.menuItem("Clear In and Out", "", false, replay != null)) session?.execute(SetInOutPoints(0L, 0L))
            if (ImGui.menuItem("Loop In to Out", "", context.loopPlayback, replay != null)) context.loopPlayback =
                !context.loopPlayback
            ImGui.separator()
            if (ImGui.menuItem("Add Marker", "M", false, replay != null)) replay?.let {
                addMarker(
                    session,
                    it.positionNanos
                )
            }
            ImGui.endMenu()
        }
        if (ImGui.beginMenu("Camera")) {
            val control = context.host.camera
            for (mode in CameraMode.entries) {
                if (ImGui.menuItem(mode.label, "", control.settings.mode == mode)) {
                    control.settings.mode = mode
                    control.apply()
                }
            }
            ImGui.separator()
            if (ImGui.menuItem(
                    "Add Keyframe at Playhead",
                    "Ctrl+K",
                    false,
                    session != null
                )
            ) session?.keyframeAtPlayhead(control.currentPose())
            if (ImGui.menuItem(
                    "Add Keyframe After Last",
                    "Ctrl+Shift+K",
                    false,
                    session != null
                )
            ) addKeyframeAfterLast()
            if (ImGui.menuItem(
                    if (recordingPath) "Stop Recording Flight" else "Record Flight as Keyframes",
                    "Ctrl+Shift+R",
                    recordingPath,
                    session != null
                )
            ) toggleRecordPath()
            if (ImGui.menuItem("Previous Keyframe", ",", false, session != null)) jumpKeyframe(-1)
            if (ImGui.menuItem("Next Keyframe", ".", false, session != null)) jumpKeyframe(1)
            ImGui.separator()
            if (ImGui.menuItem("Frame Selection", "F", false, session != null)) frameSelection()
            if (ImGui.menuItem("Snap to Recorder", "", false, replay != null)) snapToRecorder()
            ImGui.endMenu()
        }
        if (ImGui.beginMenu("View")) {
            for (tool in SceneTool.entries) if (ImGui.menuItem(
                    "${tool.label} Tool",
                    tool.key,
                    context.tool == tool
                )
            ) context.tool = tool
            if (ImGui.menuItem(if (context.localSpace) "Local Axes" else "World Axes", "X")) context.localSpace =
                !context.localSpace
            ImGui.separator()
            if (ImGui.menuItem("Fullscreen Scene", "Tab", fullscreen)) fullscreen = !fullscreen
            if (ImGui.menuItem(
                    "Camera Path",
                    "H",
                    context.host.camera.settings.showPath
                )
            ) context.host.camera.settings.showPath = !context.host.camera.settings.showPath
            if (ImGui.menuItem("Scene Gizmos", "", context.ui.sceneGizmos)) context.ui.sceneGizmos =
                !context.ui.sceneGizmos
            if (ImGui.menuItem("Camera Preview", "", context.ui.cameraPreview)) context.ui.cameraPreview =
                !context.ui.cameraPreview
            if (ImGui.menuItem("Orientation Gizmo", "", context.ui.orientationGizmo)) context.ui.orientationGizmo =
                !context.ui.orientationGizmo
            if (ImGui.menuItem("Entity Outlines", "", context.ui.entityBoxes)) context.ui.entityBoxes =
                !context.ui.entityBoxes
            if (ImGui.menuItem("Keyframe Labels", "", context.ui.keyframeLabels)) context.ui.keyframeLabels =
                !context.ui.keyframeLabels
            if (ImGui.menuItem("Scene Status", "", context.ui.viewportOverlay)) context.ui.viewportOverlay =
                !context.ui.viewportOverlay
            ImGui.separator()
            if (ImGui.menuItem("Center Guide", "", context.visuals.centerGuide)) context.visuals.centerGuide =
                !context.visuals.centerGuide
            if (ImGui.menuItem("Rule of Thirds", "", context.visuals.thirdsGuide)) context.visuals.thirdsGuide =
                !context.visuals.thirdsGuide
            ImGui.separator()
            if (ImGui.beginMenu("Interface Scale")) {
                val current = context.host.uiScale
                for (scale in UI_SCALES) {
                    if (ImGui.menuItem(
                            "${(scale * 100).toInt()}%",
                            "",
                            abs(current - scale) < 0.01f
                        )
                    ) context.host.uiScale = scale
                }
                ImGui.endMenu()
            }
            if (ImGui.menuItem("Reset Layout")) resetLayoutRequested = true
            ImGui.endMenu()
        }
        if (ImGui.beginMenu("Window")) {
            for (panel in panels) {
                if (ImGui.menuItem(panel.title, "", panel.open.get())) togglePanel(panel.title)
            }
            ImGui.endMenu()
        }
        if (ImGui.beginMenu("Help")) {
            if (ImGui.menuItem("Keyboard Shortcuts", "F1")) {
                closeDialogs()
                shortcutsOpen.set(true)
            }
            if (ImGui.menuItem("Settings")) context.openPanel("Settings")
            ImGui.endMenu()
        }
        ImGui.endMainMenuBar()
    }

    private fun shortcutsWindow() {
        shortcutsDialog.draw(shortcutsOpen, {
            val groups = ArrayList<MutableList<Pair<String, String>>>()
            for (entry in SHORTCUTS) {
                if (entry.first.isEmpty()) groups += mutableListOf(entry) else groups.last() += entry
            }
            val column = (ImGui.getContentRegionAvailX() - EditorFonts.px(24f)) / 2f
            val split = (groups.size + 1) / 2
            for (half in 0 until 2) {
                if (half == 1) ImGui.sameLine(0f, EditorFonts.px(24f))
                ImGui.beginGroup()
                for (group in groups.drop(half * split).take(split)) {
                    Widgets.header(group.first().second)
                    if (ImGui.beginTable(
                            "shortcuts-${group.first().second}",
                            2,
                            ImGuiTableFlags.SizingFixedFit,
                            column,
                            0f
                        )
                    ) {
                        ImGui.tableSetupColumn("keys", ImGuiTableColumnFlags.WidthFixed, EditorFonts.px(150f))
                        ImGui.tableSetupColumn(
                            "action",
                            ImGuiTableColumnFlags.WidthFixed,
                            column - EditorFonts.px(150f)
                        )
                        for ((keys, action) in group.drop(1)) {
                            ImGui.tableNextRow()
                            ImGui.tableNextColumn()
                            EditorFonts.with(EditorFonts.bodyMedium) { ImGui.textUnformatted(keys) }
                            ImGui.tableNextColumn()
                            ImGui.pushTextWrapPos(0f)
                            Widgets.mutedText(action)
                            ImGui.popTextWrapPos()
                        }
                        ImGui.endTable()
                    }
                    ImGui.dummy(0f, EditorFonts.px(4f))
                }
                ImGui.endGroup()
            }
        })
    }

    private fun statusBar(frame: FrameContext) {
        val viewport = ImGui.getMainViewport()
        val height = STATUS_HEIGHT
        ImGui.setNextWindowPos(viewport.workPosX, viewport.workPosY + viewport.workSizeY - height, ImGuiCond.Always)
        ImGui.setNextWindowSize(viewport.workSizeX, height, ImGuiCond.Always)
        val flags =
            ImGuiWindowFlags.NoDecoration or ImGuiWindowFlags.NoDocking or ImGuiWindowFlags.NoSavedSettings or ImGuiWindowFlags.NoFocusOnAppearing or ImGuiWindowFlags.NoNav or ImGuiWindowFlags.NoBringToFrontOnFocus or ImGuiWindowFlags.NoScrollbar
        ImGui.pushStyleVar(ImGuiStyleVar.WindowRounding, 0f)
        ImGui.pushStyleVar(ImGuiStyleVar.WindowBorderSize, 0f)
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, EditorFonts.px(12f), 0f)
        ImGui.pushStyleColor(ImGuiCol.WindowBg, EditorTheme.APP_BG.u32)
        try {
            if (ImGui.begin("##statusbar", flags)) {
                val draw = ImGui.getWindowDrawList()
                draw.addLine(
                    viewport.workPosX,
                    ImGui.getWindowPosY(),
                    viewport.workPosX + viewport.workSizeX,
                    ImGui.getWindowPosY(),
                    EditorTheme.SEPARATOR.u32,
                    1f
                )
                val session = context.session
                val messageActive = context.statusLine.isNotEmpty() && frame.nowNanos <= context.statusUntilNanos
                val left = if (messageActive) context.statusLine else context.hint
                EditorFonts.with(EditorFonts.small) {
                    val textY = (height - ImGui.getFontSize()) / 2f
                    ImGui.setCursorPosY(textY)
                    Widgets.dotted(
                        left.split("    ").map { it.trim() }.filter { it.isNotEmpty() },
                        if (messageActive) EditorTheme.TEXT.u32 else EditorTheme.TEXT_DIM.u32
                    )
                    if (session != null) {
                        val parts = ArrayList<String>(4)
                        parts += if (session.project.dirty) (if (context.ui.autosave) "Autosaving" else "Unsaved") else "Saved"
                        val width = Widgets.dottedWidth(parts)
                        ImGui.sameLine(ImGui.getWindowWidth() - width - EditorFonts.px(12f))
                        ImGui.setCursorPosY(textY)
                        Widgets.dotted(
                            parts,
                            if (session.project.dirty && !context.ui.autosave) EditorTheme.WARNING.u32 else EditorTheme.TEXT_DIM.u32
                        )
                    }
                }
            }
            ImGui.end()
        } finally {
            ImGui.popStyleColor()
            ImGui.popStyleVar(3)
        }
        toasts(frame)
    }

    private fun toasts(frame: FrameContext) {
        val active = context.toasts
        if (active.isEmpty()) return
        val now = frame.nowNanos
        active.removeIf { now > it.untilNanos }
        val rect = frameRect
        val viewport = ImGui.getMainViewport()
        val centerX = rect?.let { it.x + it.width / 2f } ?: (viewport.workPosX + viewport.workSizeX / 2f)
        var y = (rect?.let { it.y + it.height }
            ?: (viewport.workPosY + viewport.workSizeY - STATUS_HEIGHT)) - EditorFonts.px(24f)
        val draw = ImGui.getForegroundDrawList()
        for (toast in active.asReversed()) {
            val remaining = toast.untilNanos - now
            val alpha = (remaining.toDouble() / Nanos.ofMillis(300)).coerceIn(0.0, 1.0).toFloat()
            EditorFonts.with(EditorFonts.bodyMedium) {
                val width = Widgets.textWidth(toast.text)
                val padX = EditorFonts.px(16f)
                val padY = EditorFonts.px(8f)
                val height = ImGui.getFontSize() + padY * 2f
                val x = centerX - width / 2f
                y -= height
                draw.addRectFilled(
                    x - padX,
                    y,
                    x + width + padX,
                    y + height,
                    EditorTheme.PANEL_RAISED.u32(0.96f * alpha),
                    height / 2f
                )
                draw.addRect(
                    x - padX,
                    y,
                    x + width + padX,
                    y + height,
                    EditorTheme.BORDER_SOFT.u32(0.16f * alpha),
                    height / 2f
                )
                draw.addText(x, y + padY, EditorTheme.TEXT.u32(alpha), toast.text)
                y -= EditorFonts.px(6f)
            }
        }
    }

    private fun updateHint() {
        val session = context.session ?: run {
            context.hint = ""
            return
        }
        val selected = session.selection.keyframeTimes.size
        context.hint = when {
            context.host.camera.gestureActive -> "WASD fly    Space up    Shift down    Ctrl fast    Wheel speed"
            selected > 0 -> when (context.tool) {
                SceneTool.MOVE -> "Move    drag arrows or planes    Ctrl snaps to half blocks    Delete removes"
                SceneTool.ROTATE -> "Rotate    drag a ring    Ctrl snaps to 15 degrees"
                SceneTool.SCALE -> if (selected == 1) "Scale    drag the centre cube to change FOV" else "Scale    drag the centre cube or an axis cube"
                SceneTool.VIEW -> "View    click keyframes to select    drag to box select"
            }

            context.selectedEntityId?.let {
                PoseTools.poseable(
                    session,
                    it
                )
            } == true && context.tool == SceneTool.ROTATE ->
                if (context.selectedBodyPart == null) "Pose    click a limb to attach the gizmo    Esc deselects"
                else "Pose ${context.selectedBodyPart!!.label.lowercase()}    drag a ring    Ctrl snaps to 15 degrees    keys at the playhead"

            else -> ""
        }
    }

    private fun shuttle(direction: Int) {
        val replay = context.replay ?: return
        if (direction > 0) {
            replay.speed =
                if (replay.speed < 0) 1.0 else if (replay.playing) minOf(replay.speed * 2.0, 32.0) else replay.speed
        } else {
            replay.speed =
                if (replay.speed > 0) -1.0 else if (replay.playing) maxOf(replay.speed * 2.0, -32.0) else replay.speed
        }
        replay.play()
    }

    private fun jumpKeyframe(direction: Int) {
        val session = context.session ?: return
        val replay = session.replay ?: return
        val target =
            if (direction < 0) session.project.camera.previousKeyframeTime(replay.positionNanos) else session.project.camera.nextKeyframeTime(
                replay.positionNanos
            )
        if (target != null) {
            replay.seek(target)
            session.selection = Selection(keyframeTimes = setOf(target))
        }
    }

    private fun recordPath() {
        val session = context.session
        val replay = session?.replay
        if (!recordingPath || session == null || replay == null) return
        if (!replay.playing) return
        val position = replay.positionNanos
        if (position - lastRecordedNanos < RECORD_INTERVAL && lastRecordedNanos >= 0L) return
        lastRecordedNanos = position
        val existing = session.project.camera.keyframeAt(position)
        session.execute(
            SetCameraKeyframe(
                position,
                context.host.camera.currentPose(),
                existing?.easing ?: session.defaultEasing,
                SegmentMode.CATMULL_ROM
            )
        )
    }

    private fun toggleRecordPath() {
        val session = context.session ?: return
        val replay = session.replay ?: return
        recordingPath = !recordingPath
        lastRecordedNanos = -1L
        if (recordingPath) {
            val lane = session.project.lane(LaneKind.CAMERA)
            if (!lane.muted) session.execute(SetLaneState(LaneKind.CAMERA, lane.copy(muted = true)))
            if (context.host.camera.settings.mode != CameraMode.FREE) {
                context.host.camera.settings.mode = CameraMode.FREE
                context.host.camera.apply()
            }
            if (!replay.playing) replay.play()
            context.status("Recording your flight    Ctrl+Shift+R stops")
        } else {
            replay.pause()
            val lane = session.project.lane(LaneKind.CAMERA)
            if (lane.muted) session.execute(SetLaneState(LaneKind.CAMERA, lane.copy(muted = false)))
            context.status("Recorded ${session.project.camera.keyframeTimes().size} keyframes    Simplify from the Camera track menu")
        }
    }

    private fun addKeyframeAfterLast() {
        val session = context.session ?: return
        val replay = session.replay ?: return
        val last = session.project.camera.keyframeTimes().lastOrNull()
        val time = if (last == null) replay.positionNanos else minOf(replay.endNanos, last + AFTER_LAST_GAP)
        replay.seek(time)
        session.execute(
            SetCameraKeyframe(
                time,
                context.host.camera.currentPose(),
                session.defaultEasing,
                session.defaultKeyframeMode
            )
        )
        session.selection = Selection(keyframeTimes = setOf(time))
        val lane = session.project.lane(LaneKind.CAMERA)
        if (lane.muted) session.execute(SetLaneState(LaneKind.CAMERA, lane.copy(muted = false)))
    }

    private fun snapToRecorder() {
        val replay = context.replay ?: return
        val control = context.host.camera
        val recorder = replay.shadow.localPlayer
        if (!recorder.hasPosition) return
        if (control.settings.mode != CameraMode.FREE) {
            control.settings.mode = CameraMode.FREE
            control.apply()
        }
        control.teleport(
            CameraPose(
                Vector3d(recorder.x, recorder.y + control.settings.eyeHeight, recorder.z),
                Rotation(recorder.yaw.toDouble(), recorder.pitch.toDouble())
            )
        )
    }

    private fun frameSelection() {
        val session = context.session ?: return
        val control = context.host.camera
        tools.hoveredEntity?.let {
            control.frame(it.x, it.y + 1.0, it.z, 2.0)
            context.status("Framed ${it.name}")
            return
        }
        val keyframes = session.selection.keyframeTimes.mapNotNull { session.project.camera.keyframeAt(it) }
        if (keyframes.isNotEmpty()) {
            val center = Vector3d()
            for ((_, pose) in keyframes) center.add(pose.position)
            center.div(keyframes.size.toDouble())
            var radius = 1.0
            for ((_, pose) in keyframes) radius = maxOf(radius, pose.position.distance(center))
            control.frame(center.x, center.y, center.z, radius)
            return
        }
        val shadow = context.replay?.shadow ?: return
        val selected = context.selectedEntityId
        val entity = selected?.let { shadow.entities[it] }
        if (entity != null) {
            control.frame(entity.x, entity.y + 1.0, entity.z, 2.0)
            return
        }
        val local = shadow.localPlayer
        if (local.hasPosition) control.frame(local.x, local.y + 1.0, local.z, 2.0)
    }

    private fun jumpMarker(direction: Int) {
        val session = context.session ?: return
        val replay = session.replay ?: return
        val markers = session.project.markers.sortedBy { it.nanos }
        val target =
            if (direction < 0) markers.lastOrNull { it.nanos < replay.positionNanos - Nanos.ofMillis(1) } else markers.firstOrNull {
                it.nanos > replay.positionNanos + Nanos.ofMillis(1)
            }
        if (target != null) {
            replay.seek(target.nanos)
            session.selection = Selection(markerIds = setOf(target.id))
        }
    }

    private fun jumpEvent(direction: Int) {
        val session = context.session ?: return
        val replay = session.replay ?: return
        val events = session.events.events
        val target =
            if (direction < 0) events.lastOrNull { it.nanos < replay.positionNanos - Nanos.ofMillis(1) } else events.firstOrNull {
                it.nanos > replay.positionNanos + Nanos.ofMillis(1)
            }
        if (target != null) {
            replay.seek(target.nanos)
            context.status(target.label)
        }
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

    private fun shortcuts() {
        val io = ImGui.getIO()
        if (dialogOpen) return
        if (io.keyCtrl && ImGui.isKeyPressed(ImGuiKey.P, false)) palette.toggle()
        if (palette.open) return
        if (io.wantTextInput) return
        if (ImGui.isKeyPressed(ImGuiKey.F1, false)) {
            closeDialogs()
            shortcutsOpen.set(true)
        }
        if (ImGui.isKeyPressed(ImGuiKey.F2, false)) exportPanel()?.screenshotNow()
        if (io.keyCtrl && ImGui.isKeyPressed(ImGuiKey.E, false)) exportPanel()?.startNow()
        if (io.keyCtrl && ImGui.isKeyPressed(ImGuiKey.F, false)) context.openPanel("Search")
        val session = context.session ?: return
        val replay = session.replay ?: return
        val frameNanos = context.timeline.frameNanos()
        val flying = context.host.camera.gestureActive
        if (ImGui.isKeyPressed(ImGuiKey.Tab, false)) fullscreen = !fullscreen
        if (!flying && !io.keyCtrl && !io.keyAlt) {
            if (ImGui.isKeyPressed(ImGuiKey.Q, false)) context.tool = SceneTool.VIEW
            if (ImGui.isKeyPressed(ImGuiKey.W, false)) context.tool = SceneTool.MOVE
            if (ImGui.isKeyPressed(ImGuiKey.E, false)) context.tool = SceneTool.ROTATE
            if (ImGui.isKeyPressed(ImGuiKey.R, false)) context.tool = SceneTool.SCALE
            if (ImGui.isKeyPressed(ImGuiKey.X, false)) context.localSpace = !context.localSpace
        }
        val flyingInViewport =
            context.host.camera.settings.mode == CameraMode.FREE && !context.host.camera.pathActive && mouseOverViewport()
        if (ImGui.isKeyPressed(ImGuiKey.Space, false) && !flyingInViewport) {
            if (io.keyShift) {
                replay.seek(session.project.inPointNanos)
                replay.play()
            } else {
                replay.togglePlaying()
            }
        }
        if (flying) return
        if (ImGui.isKeyPressed(ImGuiKey.K, false) && !io.keyCtrl) replay.pause()
        if (ImGui.isKeyPressed(ImGuiKey.L, false)) shuttle(1)
        if (ImGui.isKeyPressed(ImGuiKey.J, false)) shuttle(-1)
        if (ImGui.isKeyPressed(ImGuiKey.LeftArrow)) replay.seekRelative(-(if (io.keyShift) Nanos.PER_TICK else frameNanos))
        if (ImGui.isKeyPressed(ImGuiKey.RightArrow)) replay.seekRelative(if (io.keyShift) Nanos.PER_TICK else frameNanos)
        if (ImGui.isKeyPressed(ImGuiKey.Home, false)) replay.seek(0L)
        if (ImGui.isKeyPressed(ImGuiKey.End, false)) replay.seek(replay.endNanos)
        if (ImGui.isKeyPressed(ImGuiKey.I, false)) session.execute(
            SetInOutPoints(
                replay.positionNanos,
                maxOf(replay.positionNanos, session.project.outPointNanos)
            )
        )
        if (ImGui.isKeyPressed(ImGuiKey.O, false)) session.execute(
            SetInOutPoints(
                minOf(
                    session.project.inPointNanos,
                    replay.positionNanos
                ), replay.positionNanos
            )
        )
        if (ImGui.isKeyPressed(ImGuiKey.M, false) && !io.keyCtrl) addMarker(session, replay.positionNanos)
        if (ImGui.isKeyPressed(ImGuiKey.Z, false) && !io.keyCtrl) {
            val view = context.timeline
            if (io.keyShift) {
                view.zoom = 1.0
                view.offsetNanos = 0L
            } else {
                zoomToInOut(session)
            }
        }
        if (!io.keyCtrl && !io.keyAlt && session.selection.markerIds.isNotEmpty()) {
            for ((index, key) in DIGIT_KEYS.withIndex()) {
                if (ImGui.isKeyPressed(key, false)) {
                    for (id in session.selection.markerIds) {
                        val marker = session.project.marker(id) ?: continue
                        session.execute(ReplaceMarker(id, marker.copy(color = MARKER_COLORS[index])))
                    }
                }
            }
        }
        if (ImGui.isKeyPressed(
                ImGuiKey.Comma,
                false
            )
        ) if (io.keyShift) jumpMarker(-1) else if (io.keyAlt) jumpEvent(-1) else jumpKeyframe(-1)
        if (ImGui.isKeyPressed(
                ImGuiKey.Period,
                false
            )
        ) if (io.keyShift) jumpMarker(1) else if (io.keyAlt) jumpEvent(1) else jumpKeyframe(1)
        if (ImGui.isKeyPressed(ImGuiKey.F, false) && !io.keyCtrl) if (io.keyShift) context.timeline.followPlayhead =
            !context.timeline.followPlayhead else if (graphPanel()?.frame(session) != true) frameSelection()
        if (ImGui.isKeyPressed(ImGuiKey.F9, false)) {
            val selection = session.selection
            if (selection.keyframeTimes.isNotEmpty() || selection.valueKeys.isNotEmpty()) {
                val command = when {
                    io.keyCtrl && io.keyShift -> EaseKeyframes.easeOut(selection.keyframeTimes, selection.valueKeys)
                    io.keyShift -> EaseKeyframes.easeIn(selection.keyframeTimes, selection.valueKeys)
                    else -> EaseKeyframes.easyEase(selection.keyframeTimes, selection.valueKeys)
                }
                session.execute(command)
                context.status(command.label)
            }
        }
        if (io.keyCtrl && !io.keyShift && ImGui.isKeyPressed(ImGuiKey.G, false)) togglePanel("Graph Editor")
        if (ImGui.isKeyPressed(ImGuiKey.H, false) && !io.keyCtrl) context.host.camera.settings.showPath =
            !context.host.camera.settings.showPath
        if (io.keyCtrl && ImGui.isKeyPressed(
                ImGuiKey.K,
                false
            )
        ) if (io.keyShift) addKeyframeAfterLast() else session.keyframeAtPlayhead(context.host.camera.currentPose())
        if (io.keyCtrl && io.keyShift && ImGui.isKeyPressed(ImGuiKey.R, false)) toggleRecordPath()
        if (io.keyCtrl && ImGui.isKeyPressed(ImGuiKey.D, false)) duplicateSelectedKeyframe(session)
        if (io.keyCtrl && ImGui.isKeyPressed(ImGuiKey.C, false)) copySelection(session)
        if (io.keyCtrl && ImGui.isKeyPressed(ImGuiKey.X, false)) {
            copySelection(session)
            session.deleteSelection()
        }
        if (io.keyCtrl && ImGui.isKeyPressed(ImGuiKey.V, false)) context.clipboard?.let {
            val relativeToPose = if (io.keyShift) context.host.camera.currentPose() else null
            it.paste(session, replay.positionNanos, relativeToPose)
            context.status(if (relativeToPose != null) "Pasted ${it.count} keyframes relative to view" else "Pasted ${it.count} keyframes")
        }
        if (ImGui.isKeyPressed(ImGuiKey.Escape, false)) {
            if (!session.selection.isEmpty) session.selection = Selection.NONE else context.selectEntity(null)
        }
        if (io.keyCtrl && ImGui.isKeyPressed(ImGuiKey.Z, false)) if (io.keyShift) redo(session) else undo(session)
        if (io.keyCtrl && ImGui.isKeyPressed(ImGuiKey.Y, false)) redo(session)
        if (io.keyCtrl && ImGui.isKeyPressed(ImGuiKey.S, false)) saveProject()
    }

    private fun paletteCommands(): List<PaletteCommand> {
        val commands = ArrayList<PaletteCommand>(96)
        val session = context.session
        val replay = session?.replay
        val control = context.host.camera
        val recording = context.host.recording.status()
        fun add(
            title: String,
            group: String,
            icon: Icon,
            shortcut: String = "",
            enabled: Boolean = true,
            run: () -> Unit
        ) {
            commands += PaletteCommand(title, group, shortcut, icon, enabled, run)
        }
        if (recording.recording) add("Stop recording", "Recorder", Icon.STOP) { context.host.recording.stop() }
        else add(
            "Start recording",
            "Recorder",
            Icon.RECORD,
            enabled = recording.connected
        ) { context.host.recording.start() }
        if (session == null) {
            val recent = context.host.replay.listRecordings().firstOrNull()
            add("Open latest recording", "Library", Icon.FOLDER, enabled = recent != null) {
                recent?.let { path ->
                    context.host.later {
                        if (!context.host.replay.open(path)) context.status(
                            context.host.replay.lastError() ?: "Could not open ${path.fileName}"
                        )
                    }
                }
            }
            return commands
        }
        val hasReplay = replay != null
        for (tool in SceneTool.entries) add(
            "${tool.label} tool",
            "Tools",
            tool.icon,
            tool.key,
            context.tool != tool
        ) { context.tool = tool }
        add(
            if (context.localSpace) "Use world axes" else "Use local axes",
            "Tools",
            Icon.GLOBE,
            "X"
        ) { context.localSpace = !context.localSpace }
        add("Play or pause", "Playback", Icon.PLAY, "Space", hasReplay) { replay?.togglePlaying() }
        add(
            "Play from in point",
            "Playback",
            Icon.PLAY,
            "Shift+Space",
            hasReplay
        ) { replay?.let { it.seek(session.project.inPointNanos); it.play() } }
        add("Shuttle reverse", "Playback", Icon.REWIND, "J", hasReplay) { shuttle(-1) }
        add("Shuttle forward", "Playback", Icon.FAST_FORWARD, "L", hasReplay) { shuttle(1) }
        add(
            "Step frame back",
            "Playback",
            Icon.STEP_BACK,
            "Left",
            hasReplay
        ) { replay?.seekRelative(-context.timeline.frameNanos()) }
        add(
            "Step frame forward",
            "Playback",
            Icon.STEP_FORWARD,
            "Right",
            hasReplay
        ) { replay?.seekRelative(context.timeline.frameNanos()) }
        add("Jump to start", "Playback", Icon.SKIP_START, "Home", hasReplay) { replay?.seek(0L) }
        add("Jump to end", "Playback", Icon.SKIP_END, "End", hasReplay) { replay?.let { it.seek(it.endNanos) } }
        add(
            if (context.loopPlayback) "Loop in to out: on" else "Loop in to out: off",
            "Playback",
            Icon.LOOP,
            "",
            hasReplay
        ) { context.loopPlayback = !context.loopPlayback }
        for (speed in doubleArrayOf(0.25, 0.5, 1.0, 2.0, 4.0, 8.0)) {
            add(
                "Speed ${if (speed >= 1.0) "${speed.toInt()}x" else "${speed}x"}",
                "Playback",
                Icon.GAUGE,
                "",
                hasReplay
            ) { replay?.speed = speed }
        }
        add(
            "Set in point",
            "Edit",
            Icon.MARK_IN,
            "I",
            hasReplay
        ) {
            replay?.let {
                session.execute(
                    SetInOutPoints(
                        it.positionNanos,
                        maxOf(it.positionNanos, session.project.outPointNanos)
                    )
                )
            }
        }
        add(
            "Set out point",
            "Edit",
            Icon.MARK_OUT,
            "O",
            hasReplay
        ) {
            replay?.let {
                session.execute(
                    SetInOutPoints(
                        minOf(session.project.inPointNanos, it.positionNanos),
                        it.positionNanos
                    )
                )
            }
        }
        add("Clear in and out", "Edit", Icon.CLOSE, "", hasReplay) { session.execute(SetInOutPoints(0L, 0L)) }
        add("Add marker", "Edit", Icon.MARKER, "M", hasReplay) { replay?.let { addMarker(session, it.positionNanos) } }
        add(
            "Undo ${session.commands.undoLabel ?: ""}".trim(),
            "Edit",
            Icon.UNDO,
            "Ctrl+Z",
            session.commands.canUndo
        ) { undo(session) }
        add(
            "Redo ${session.commands.redoLabel ?: ""}".trim(),
            "Edit",
            Icon.REDO,
            "Ctrl+Y",
            session.commands.canRedo
        ) { redo(session) }
        add("Copy keyframes", "Edit", Icon.COPY, "Ctrl+C", !session.selection.isEmpty) { copySelection(session) }
        add(
            "Paste keyframes",
            "Edit",
            Icon.PASTE,
            "Ctrl+V",
            context.clipboard != null && hasReplay
        ) { context.clipboard?.let { clip -> replay?.let { clip.paste(session, it.positionNanos) } } }
        add(
            "Paste keyframes relative to view",
            "Edit",
            Icon.PASTE,
            "Ctrl+Shift+V",
            context.clipboard != null && hasReplay
        ) {
            context.clipboard?.let { clip ->
                replay?.let { clip.paste(session, it.positionNanos, context.host.camera.currentPose()) }
            }
        }
        add("Duplicate keyframe", "Edit", Icon.COPY, "Ctrl+D", !session.selection.isEmpty) {
            duplicateSelectedKeyframe(
                session
            )
        }
        add("Delete selection", "Edit", Icon.TRASH, "Del", !session.selection.isEmpty) { session.deleteSelection() }
        add("Frame selection", "Scene", Icon.FIT, "F") { frameSelection() }
        add("Snap camera to recorder", "Scene", Icon.USER, "", hasReplay) { snapToRecorder() }
        add("Zoom timeline to in and out", "Timeline", Icon.ZOOM_IN, "Z", hasReplay) { zoomToInOut(session) }
        add("Reset timeline zoom", "Timeline", Icon.ZOOM_OUT, "Shift+Z") {
            context.timeline.zoom = 1.0
            context.timeline.offsetNanos = 0L
        }
        add(
            if (context.timeline.followPlayhead) "Follow playhead: on" else "Follow playhead: off",
            "Timeline",
            Icon.FOLLOW,
            "Shift+F"
        ) { context.timeline.followPlayhead = !context.timeline.followPlayhead }
        add(
            if (context.timeline.snapToTicks) "Snap: on" else "Snap: off",
            "Timeline",
            Icon.MAGNET
        ) { context.timeline.snapToTicks = !context.timeline.snapToTicks }
        add("Previous marker", "Timeline", Icon.MARKER, "Shift+,") { jumpMarker(-1) }
        add("Next marker", "Timeline", Icon.MARKER, "Shift+.") { jumpMarker(1) }
        add("Previous event", "Timeline", Icon.CLOCK, "Alt+,") { jumpEvent(-1) }
        add("Next event", "Timeline", Icon.CLOCK, "Alt+.") { jumpEvent(1) }
        for (mode in CameraMode.entries) {
            add(
                "Camera: ${mode.label}",
                "Camera",
                if (mode == CameraMode.FREE) Icon.CAMERA else Icon.TARGET,
                "",
                control.settings.mode != mode
            ) {
                control.settings.mode = mode
                control.apply()
            }
        }
        add(
            "Add keyframe at playhead",
            "Camera",
            Icon.KEYFRAME_ADD,
            "Ctrl+K"
        ) { session.keyframeAtPlayhead(control.currentPose()) }
        add("Add keyframe after last", "Camera", Icon.KEYFRAME_ADD, "Ctrl+Shift+K") { addKeyframeAfterLast() }
        add(if (session.autoKey) "Auto key off" else "Auto key on", "Camera", Icon.AUTO_KEY) { toggleAutoKey() }
        add("Easy ease selected keyframes", "Camera", Icon.GRAPH, "F9", !session.selection.isEmpty) {
            session.execute(EaseKeyframes.easyEase(session.selection.keyframeTimes, session.selection.valueKeys))
        }
        add("Ease into selected keyframes", "Camera", Icon.GRAPH, "Shift+F9", !session.selection.isEmpty) {
            session.execute(EaseKeyframes.easeIn(session.selection.keyframeTimes, session.selection.valueKeys))
        }
        add("Ease out of selected keyframes", "Camera", Icon.GRAPH, "Ctrl+Shift+F9", !session.selection.isEmpty) {
            session.execute(EaseKeyframes.easeOut(session.selection.keyframeTimes, session.selection.valueKeys))
        }
        add("Graph Editor", "Window", Icon.GRAPH, "Ctrl+G") { context.openPanel("Graph Editor") }
        add(
            if (recordingPath) "Stop recording flight" else "Record flight as keyframes",
            "Camera",
            Icon.PATH,
            "Ctrl+Shift+R"
        ) { toggleRecordPath() }
        add("Previous keyframe", "Camera", Icon.KEYFRAME, ",") { jumpKeyframe(-1) }
        add("Next keyframe", "Camera", Icon.KEYFRAME, ".") { jumpKeyframe(1) }
        add(
            if (control.settings.showPath) "Hide camera path" else "Show camera path",
            "Camera",
            Icon.PATH,
            "H"
        ) { control.settings.showPath = !control.settings.showPath }
        add("Export video now", "Export", Icon.EXPORT, "Ctrl+E", context.exports != null) { exportPanel()?.startNow() }
        add("Screenshot", "Export", Icon.IMAGE, "F2", context.exports != null) { exportPanel()?.screenshotNow() }
        add("Export settings", "Export", Icon.SLIDERS) { context.openPanel("Export") }
        add("Save trimmed copy", "File", Icon.SCISSORS, "", context.exports != null) { trimRecording() }
        add("Render POV proxy video", "File", Icon.FILM, "", context.exports != null) { exportPanel()?.renderProxy() }
        add("Trim segment to in and out", "File", Icon.SCISSORS) { trimSegmentToInOut() }
        add("Save project", "File", Icon.SAVE, "Ctrl+S") { saveProject() }
        add("Close replay", "File", Icon.CLOSE) { context.host.later { context.host.replay.close() } }
        for (panel in panels) {
            add(if (panel.open.get()) "Hide ${panel.title}" else "Show ${panel.title}", "Window", panel.icon) {
                if (panel.open.get()) panel.open.set(false) else context.openPanel(panel.title)
            }
        }
        add(
            if (fullscreen) "Exit fullscreen scene" else "Fullscreen scene",
            "View",
            Icon.FULLSCREEN,
            "Tab"
        ) { fullscreen = !fullscreen }
        add(
            if (context.ui.cameraPreview) "Hide camera preview" else "Show camera preview",
            "View",
            Icon.MONITOR
        ) { context.ui.cameraPreview = !context.ui.cameraPreview }
        add(
            if (context.visuals.centerGuide) "Hide center guide" else "Show center guide",
            "View",
            Icon.TARGET
        ) { context.visuals.centerGuide = !context.visuals.centerGuide }
        add(
            if (context.visuals.thirdsGuide) "Hide rule of thirds" else "Show rule of thirds",
            "View",
            Icon.GRID
        ) { context.visuals.thirdsGuide = !context.visuals.thirdsGuide }
        add("Reset layout", "View", Icon.REFRESH) { resetLayoutRequested = true }
        add("Keyboard shortcuts", "Help", Icon.COMMAND, "F1") {
            closeDialogs()
            shortcutsOpen.set(true)
        }
        return commands
    }

    private fun zoomToInOut(session: EditorSession) {
        val replay = session.replay ?: return
        val view = context.timeline
        val duration = maxOf(1L, replay.durationNanos)
        val inNanos = session.project.inPointNanos.coerceIn(0L, duration)
        val outNanos = (if (session.project.outPointNanos > 0L) session.project.outPointNanos else duration).coerceIn(
            inNanos + 1L,
            duration
        )
        val span = maxOf(Nanos.ofMillis(200), (outNanos - inNanos) * 11 / 10)
        view.zoom = (duration.toDouble() / span).coerceAtLeast(1.0)
        view.offsetNanos = (inNanos - (span - (outNanos - inNanos)) / 2).coerceIn(0L, maxOf(0L, duration - span))
    }

    private fun copySelection(session: EditorSession) {
        val clipboard = KeyframeClipboard.capture(session) ?: return
        context.clipboard = clipboard
        context.status("Copied ${clipboard.count} keyframes")
    }

    private fun mouseOverViewport(): Boolean {
        if (ImGui.getIO().wantCaptureMouse) return false
        if (fullscreen) return true
        val rect = frameRect ?: return false
        return rect.contains(ImGui.getMousePosX(), ImGui.getMousePosY())
    }

    private fun duplicateSelectedKeyframe(session: EditorSession) {
        val replay = session.replay ?: return
        val time = session.selection.keyframeTimes.singleOrNull() ?: return
        val frame = session.project.camera.keyframeAt(time) ?: return
        session.execute(SetCameraKeyframe(replay.positionNanos, frame.pose, frame.easing, frame.mode))
        session.selection = Selection(keyframeTimes = setOf(replay.positionNanos))
    }

    private fun undo(session: EditorSession) {
        val label = session.commands.undoLabel ?: return
        if (session.commands.undo()) context.status("Undid $label")
    }

    private fun redo(session: EditorSession) {
        val label = session.commands.redoLabel ?: return
        if (session.commands.redo()) context.status("Redid $label")
    }

    private fun exportPanel(): ExportPanel? = panels.filterIsInstance<ExportPanel>().firstOrNull()

    private fun graphPanel(): GraphEditorPanel? = panels.filterIsInstance<GraphEditorPanel>().firstOrNull()

    private fun trimSegmentToInOut() {
        val session = context.session ?: return
        val replay = session.replay ?: return
        val project = session.project
        if (project.segments.isEmpty()) return
        val inNanos = project.inPointNanos.coerceIn(0L, replay.durationNanos)
        val outNanos = (if (project.outPointNanos > 0L) project.outPointNanos else replay.durationNanos).coerceIn(
            inNanos,
            replay.durationNanos
        )
        if (outNanos - inNanos < Nanos.PER_SECOND) {
            context.status("Set in and out points at least a second apart first")
            return
        }
        val spans = project.segmentSpans()
        val index = spans.indexOfFirst { inNanos >= it.first && inNanos < it.second }.takeIf { it >= 0 } ?: 0
        val span = spans[index]
        val segment = project.segments[index]
        val sourceIn = segment.inNanos + (inNanos - span.first)
        val sourceOut = segment.inNanos + (minOf(outNanos, span.second) - span.first)
        project.segments[index] =
            segment.copy(inNanos = sourceIn, outNanos = sourceOut, lengthNanos = sourceOut - sourceIn)
        project.dirty = true
        context.openPanel("Sequence")
        context.status("Segment ${index + 1} trimmed    rebuild the sequence to apply")
    }

    private fun trimRecording() {
        val session = context.session ?: return
        val replay = session.replay ?: return
        val backend = context.exports ?: return
        val start = session.project.inPointNanos.coerceIn(0L, replay.durationNanos)
        val end =
            (if (session.project.outPointNanos > 0L) session.project.outPointNanos else replay.durationNanos).coerceIn(
                start,
                replay.durationNanos
            )
        if (end - start < Nanos.PER_SECOND) {
            context.status("Set in and out points at least a second apart first")
            return
        }
        val source = session.project.recording
        val base = source.fileName.toString().substringBeforeLast('.')
        val output =
            source.resolveSibling("$base-trim-${TimeFormat.clock(start).replace(':', '-').substringBefore('.')}.recast")
        backend.queue().submit(TrimJob(RecordingTrimmer(), source, output, start, end))
        context.status("Trimming to ${output.fileName}")
    }

    private fun saveProject(quiet: Boolean = false) {
        val session = context.session ?: return
        val path = session.project.file ?: context.host.projectsDirectory.resolve(
            session.project.recording.fileName.toString().substringBeforeLast('.') + ".rcproj"
        )
        runCatching { ProjectCodec.save(session.project, path) }
            .onSuccess { if (!quiet) context.status("Saved ${path.fileName}") }
            .onFailure { context.status("Save failed: ${it.message}") }
    }

    private fun autosave(nowNanos: Long) {
        if (!context.ui.autosave) return
        val session = context.session ?: return
        val version = session.commands.version
        if (version != autosaveVersion) {
            autosaveVersion = version
            autosaveDueNanos = nowNanos + AUTOSAVE_DELAY
        }
        if (session.project.dirty && autosaveDueNanos in 1..nowNanos) {
            autosaveDueNanos = 0L
            saveProject(quiet = true)
        }
    }

    companion object {
        val AUTOSAVE_DELAY: Long = Nanos.ofSeconds(3)
        val RECORD_INTERVAL: Long = Nanos.ofMillis(200)
        val DIGIT_KEYS = intArrayOf(
            ImGuiKey._1,
            ImGuiKey._2,
            ImGuiKey._3,
            ImGuiKey._4,
            ImGuiKey._5,
            ImGuiKey._6,
            ImGuiKey._7,
            ImGuiKey._8
        )
        val MARKER_COLORS = intArrayOf(0x30D158, 0x66D4CF, 0xFFD60A, 0xFF453A, 0xBF5AF2, 0xFF9F0A, 0x8E8E93, 0xFFFFFF)
        val AFTER_LAST_GAP: Long = Nanos.ofSeconds(2)
        val DEFAULT_TABS = listOf("Hierarchy", "Timeline", "Inspector")
        val UI_SCALES = floatArrayOf(0.85f, 1f, 1.15f, 1.25f, 1.5f, 1.75f, 2f)
        const val RELAYOUT_RATIO = 1.35f
        const val GRAPH_DOCK_WAIT_FRAMES = 120
        val STATUS_HEIGHT: Float get() = EditorFonts.px(24f)
        val TOOLBAR_HEIGHT: Float get() = EditorFonts.px(40f)
        val RTC_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d  HH:mm:ss")
        val SHORTCUTS = listOf(
            "" to "Tools",
            "Q  W  E  R" to "View, Move, Rotate and Scale tools",
            "X" to "Toggle local and world axes",
            "Ctrl while dragging" to "Snap: half blocks, 15 degrees, tenths",
            "Click keyframe" to "Select   Shift adds   double-click jumps there",
            "Drag on empty scene" to "Box select keyframes",
            "Double-click the path" to "Insert a keyframe there",
            "Ctrl+A  /  Delete" to "Select all keyframes  /  delete selected",
            "F" to "Frame the hovered entity, selected keyframes or selected entity",
            "H" to "Toggle the camera path and gizmos",
            "Tab" to "Fullscreen scene",
            "" to "Scene camera",
            "Right drag" to "Look around   WASD flies   Space up   Shift down",
            "Wheel" to "Dolly toward the cursor   while flying: fly speed",
            "Alt+Left drag" to "Orbit around the point under the cursor",
            "Middle drag" to "Pan",
            "Alt+Right drag" to "Dolly in and out",
            "Ctrl  /  Alt while flying" to "Fast  /  slow",
            "" to "Playback",
            "Space" to "Play or pause   Shift plays from the in point",
            "J  K  L" to "Shuttle reverse, pause, forward   press again to double",
            "Left  /  Right" to "Step one frame   Shift steps one tick",
            "Home  /  End" to "Jump to start or end",
            ",  /  ." to "Previous or next keyframe   Shift: marker   Alt: event",
            "I  /  O" to "Set in or out point",
            "" to "Editing",
            "Ctrl+K" to "Add a camera keyframe from the current view",
            "Ctrl+Shift+K" to "Add a keyframe two seconds after the last one",
            "Ctrl+Shift+R" to "Record your flight as keyframes while playing",
            "Auto key (toolbar)" to "Moving the camera on a paused frame sets a keyframe there",
            "F9" to "Easy ease the selected keyframes   Shift: ease in   Ctrl+Shift: ease out",
            "Ctrl+G" to "Graph Editor: curves, tangent handles and speed graph",
            "Ctrl+D" to "Duplicate the selected keyframe at the playhead",
            "Ctrl+C  X  V" to "Copy, cut and paste keyframes at the playhead",
            "M" to "Add a marker   1 to 8 recolour selected markers",
            "Ctrl+Z  /  Ctrl+Y" to "Undo and redo",
            "Ctrl+S" to "Save the project",
            "Ctrl+E  /  F2" to "Start an export  /  screenshot",
            "Ctrl+P" to "Command palette",
            "" to "Timeline",
            "Wheel" to "Zoom around the cursor   Shift pans   middle drag pans",
            "Drag the ruler" to "Scrub",
            "Alt+drag keyframe" to "Duplicate",
            "Shift while dragging" to "Disable snapping",
            "Z  /  Shift+Z" to "Zoom to the in and out range  /  fit everything",
            "Shift+F" to "Follow the playhead",
            "" to "Graph Editor",
            "Drag a key" to "Move in time and value   Shift locks one axis   Ctrl rounds values",
            "Drag a handle" to "Horizontal: influence   vertical: slope or speed   Alt edits one side only",
            "Drag the brackets" to "Stretch the selected keyframes in time",
            "Double-click a curve" to "Insert a keyframe on that channel",
            "Wheel  /  Shift+wheel" to "Zoom time   /  pan   Ctrl+wheel scales values when not normalized",
            "Middle drag  /  Alt+drag" to "Pan the graph",
            "Alt+click a channel" to "Solo it   Ctrl+click selects all its keyframes",
            "F" to "Fit the selection or everything",
        )
    }
}
