package gg.sona.afterimage.editor.imgui

import gg.sona.afterimage.camera.*
import gg.sona.afterimage.camera.track.SegmentMode
import gg.sona.afterimage.core.time.Nanos
import gg.sona.afterimage.editor.*
import gg.sona.afterimage.editor.commands.*
import gg.sona.afterimage.editor.host.CameraControl
import gg.sona.afterimage.world.EntityKind
import imgui.ImGui
import imgui.flag.ImGuiInputTextFlags
import imgui.type.ImString
import org.joml.Vector3d

class CameraPanel(private val context: EditorContext) : AbstractPanel("Camera", DockArea.RIGHT, Icon.CAMERA) {
    private val presetName = ImString("", 64)
    private var presetNames: List<String>? = null
    private var presetsDirty = false

    override fun content(frame: FrameContext) {
        val control = context.host.camera
        val settings = control.settings
        val session = context.session

        val modes = CameraMode.entries
        Widgets.segmented("mode", modes.map { it.label }, modes.indexOf(settings.mode), 0f, MODE_TOOLTIPS)?.let {
            settings.mode = modes[it]
            control.apply()
        }
        ImGui.dummy(0f, EditorFonts.px(2f))
        if (settings.mode != CameraMode.FREE) targetPicker(settings, control)

        if (section(settings.mode.label, "camera.mode")) when (settings.mode) {
            CameraMode.FREE -> freeSettings(settings, control)
            CameraMode.FIRST_PERSON -> firstPersonSettings(settings, control)
            CameraMode.ORBIT -> orbitSettings(settings, control)
            CameraMode.FOLLOW -> followSettings(settings, control)
            CameraMode.CHASE -> chaseSettings(settings, control)
        }

        if (session != null) pathSection(session, control)

        if (section("Lens", "camera.lens", trailing = if (settings.overrideFov) String.format("%.0f°", settings.fov) else null) && Widgets.beginProperties("lens")) {
            Widgets.property("Override FOV")
            Widgets.toggle("##ovfov", settings.overrideFov)?.let {
                settings.overrideFov = it
                control.apply()
            }
            if (settings.overrideFov) {
                Widgets.property("Field of view")
                Widgets.doubleSlider("##fov", settings.fov, 10.0, 150.0, "%.0f°")?.let {
                    settings.fov = it
                    control.apply()
                }
                quickKeyframeButton(session, ValueLane.FOV, settings.fov)
            }
            Widgets.property("Roll")
            Widgets.doubleSlider("##roll", settings.roll, -180.0, 180.0, "%.1f°")?.let {
                settings.roll = it
                control.apply()
            }
            Widgets.property("Shake")
            Widgets.doubleSlider("##shake", settings.shakeStrength, 0.0, 5.0, "%.2f")
                ?.let { settings.shakeStrength = it }
            quickKeyframeButton(session, ValueLane.SHAKE, settings.shakeStrength)
            Widgets.property("Shake frequency")
            Widgets.doubleSlider("##shakefreq", settings.shakeFrequencyHz, 0.1, 30.0, "%.2f Hz")
                ?.let { settings.shakeFrequencyHz = it }
            quickKeyframeButton(session, ValueLane.SHAKE_FREQUENCY, settings.shakeFrequencyHz)
            Widgets.property("Shake on hits")
            Widgets.toggle("##shakeevents", settings.shakeOnEvents)?.let { settings.shakeOnEvents = it }
            Widgets.endProperties()
        }
        if (session != null) presets(session)
    }

    private fun quickKeyframeButton(session: EditorSession?, lane: ValueLane, value: Double) {
        if (session == null) return
        ImGui.sameLine()
        if (Widgets.iconButton(
                "qkf-${lane.name}",
                Icon.PLUS,
                ImGui.getFrameHeight(),
                "Add ${lane.label.lowercase()} keyframe here with this value",
                iconScale = 0.6f
            )
        ) {
            session.execute(SetValueKeyframe(lane, session.playheadNanos, value.coerceIn(lane.min, lane.max)))
            val state = session.project.lane(lane.kind)
            if (state.muted) session.execute(SetLaneState(lane.kind, state.copy(muted = false)))
        }
    }

    private fun aimPicker(session: EditorSession) {
        val shadow = context.replay?.world
        val recorderName = shadow?.localPlayer?.name ?: "Recorder"
        val current = session.project.aimTargetId
        val label = when (current) {
            null -> "Keyframe rotation"
            CameraSettings.TARGET_RECORDER -> recorderName
            else -> entityLabel(current)
        }
        if (Widgets.popupButton("aim", label)) {
            if (Menus.item("Keyframe rotation", "", current == null)) session.execute(SetAimTarget(null))
            if (Menus.item(recorderName, "", current == CameraSettings.TARGET_RECORDER)) session.execute(
                SetAimTarget(CameraSettings.TARGET_RECORDER)
            )
            val players =
                shadow?.entities?.values()?.filter { it.kind == EntityKind.PLAYER }?.sortedBy { entityLabel(it.id) }
                    ?: emptyList()
            if (players.isNotEmpty()) ImGui.separator()
            for (entity in players) {
                if (Menus.item(entityLabel(entity.id), "", current == entity.id)) session.execute(
                    SetAimTarget(
                        entity.id
                    )
                )
            }
            Widgets.endPopup()
        }
    }

    private fun targetPicker(settings: CameraSettings, control: CameraControl) {
        val shadow = context.replay?.world
        val recorderName = shadow?.localPlayer?.name ?: "Recorder"
        val label = if (settings.targetsRecorder()) recorderName else entityLabel(settings.targetEntityId)
        if (Widgets.popupButton("target", label)) {
            if (Menus.item(recorderName, "", settings.targetsRecorder())) {
                settings.targetEntityId = CameraSettings.TARGET_RECORDER
                control.apply()
            }
            val players =
                shadow?.entities?.values()?.filter { it.kind == EntityKind.PLAYER }?.sortedBy { entityLabel(it.id) }
                    ?: emptyList()
            if (players.isNotEmpty()) {
                ImGui.separator()
                for (entity in players) {
                    if (Menus.item(entityLabel(entity.id), "", settings.targetEntityId == entity.id)) {
                        settings.targetEntityId = entity.id
                        control.apply()
                    }
                }
            }
            val others =
                shadow?.entities?.values()?.filter { it.kind != EntityKind.PLAYER }?.sortedBy { it.kind.name }?.take(60)
                    ?: emptyList()
            if (others.isNotEmpty() && settings.mode != CameraMode.FIRST_PERSON) {
                ImGui.separator()
                for (entity in others) {
                    if (Menus.item(entityLabel(entity.id), "", settings.targetEntityId == entity.id)) {
                        settings.targetEntityId = entity.id
                        control.apply()
                    }
                }
            }
            Widgets.endPopup()
        }
    }

    private fun freeSettings(settings: CameraSettings, control: CameraControl) {
        if (Widgets.beginProperties("free")) {
            Widgets.property("Fly speed")
            Widgets.doubleSlider("##speed", settings.freeSpeed, 0.5, 100.0, "%.1f b/s")?.let { settings.freeSpeed = it }
            Widgets.property("Sensitivity")
            Widgets.doubleSlider("##sens", settings.freeSensitivity, 0.02, 1.0, "%.2f")
                ?.let { settings.freeSensitivity = it }
            Widgets.property("Acceleration", "Speed ramps up the longer a movement key is held")
            Widgets.toggle("##accel", settings.freeAcceleration)?.let { settings.freeAcceleration = it }
            Widgets.property("Easing", "Smooth starts and stops")
            Widgets.toggle("##easing", settings.freeEasing)?.let { settings.freeEasing = it }
            Widgets.property("Lock", "Locked axes ignore right-mouse fly and look input")
            val gap = EditorFonts.px(4f)
            if (Widgets.chipButton("lock-x", "X", settings.lockX)) settings.lockX = !settings.lockX
            ImGui.sameLine(0f, gap)
            if (Widgets.chipButton("lock-y", "Y", settings.lockY)) settings.lockY = !settings.lockY
            ImGui.sameLine(0f, gap)
            if (Widgets.chipButton("lock-z", "Z", settings.lockZ)) settings.lockZ = !settings.lockZ
            ImGui.sameLine(0f, gap)
            if (Widgets.chipButton("lock-yaw", "Yaw", settings.lockYaw)) settings.lockYaw = !settings.lockYaw
            ImGui.sameLine(0f, gap)
            if (Widgets.chipButton("lock-pitch", "Pitch", settings.lockPitch)) settings.lockPitch = !settings.lockPitch
            Widgets.endProperties()
        }
        if (Widgets.ghostButton("Snap to recorder")) {
            context.replay?.let { replay ->
                val recorder = replay.world.localPlayer
                control.teleport(
                    CameraPose(
                        Vector3d(recorder.x, recorder.y + settings.eyeHeight, recorder.z),
                        Rotation(recorder.yaw.toDouble(), recorder.pitch.toDouble())
                    )
                )
            }
        }
        ImGui.sameLine()
        if (Widgets.ghostButton("Snap to path")) {
            context.session?.let { session ->
                val pose = session.project.camera.poseAt(session.playheadNanos)
                if (!session.project.camera.isEmpty) control.teleport(pose)
            }
        }
    }

    private fun firstPersonSettings(settings: CameraSettings, control: CameraControl) {
        if (Widgets.beginProperties("fp")) {
            if (settings.targetsRecorder()) {
                Widgets.property(
                    "Exact camera",
                    "Off builds a synthetic camera from position and look angles so smoothing, roll and FOV overrides apply"
                )
                Widgets.toggle("##exact", settings.exactFirstPerson)?.let {
                    settings.exactFirstPerson = it
                    control.apply()
                }
            }
            Widgets.property("HUD")
            Widgets.toggle("##hud", settings.showHud)?.let { settings.showHud = it }
            Widgets.property("Hand")
            Widgets.toggle("##hand", settings.showHand)?.let { settings.showHand = it }
            Widgets.property("Block outline", "Draw the crosshair block outline the recorder saw")
            Widgets.toggle("##outline", settings.showBlockOutline)?.let { settings.showBlockOutline = it }
            if (settings.targetsRecorder()) {
                Widgets.property(
                    "Recorder screens",
                    "Replays the inventory, chests, chat and menus the recorder had open"
                )
                Widgets.toggle("##screens", settings.mirrorScreens)?.let { settings.mirrorScreens = it }
                if (settings.mirrorScreens) {
                    Widgets.property("Cursor")
                    Widgets.toggle("##cursor", settings.mirrorCursor)?.let { settings.mirrorCursor = it }
                }
            }
            Widgets.property("Player list")
            Widgets.toggle("##tab", settings.showPlayerList)?.let { settings.showPlayerList = it }
            Widgets.property(
                "Smooth entities",
                "Draws other players and mobs along their recorded path instead of the three tick catch up"
            )
            Widgets.toggle("##smoothent", settings.smoothEntities)?.let { settings.smoothEntities = it }
            if (settings.smoothEntities) {
                Widgets.property(
                    "Entity delay",
                    "How far behind the playhead entities are drawn. 150 ms matches vanilla."
                )
                Widgets.doubleSlider("##entdelay", settings.entityDelayMillis.toDouble(), 50.0, 400.0, "%.0f ms")
                    ?.let { settings.entityDelayMillis = it.toLong() }
                Widgets.property("Smooth rotation", "Also drives yaw, pitch and head turn from the recorded path")
                Widgets.toggle("##smoothrot", settings.smoothEntityRotation)?.let { settings.smoothEntityRotation = it }
            }
            Widgets.property("Hide target body")
            Widgets.toggle("##hidebody", settings.hideTargetInFirstPerson)?.let {
                settings.hideTargetInFirstPerson = it
                control.apply()
            }
            if (!settings.exactFirstPerson || !settings.targetsRecorder()) {
                Widgets.property("Interpolation")
                Widgets.enumCombo("##interp", settings.interpolation) { it.label }?.let {
                    settings.interpolation = it
                    control.apply()
                }
                Widgets.property("Smoothing", "0 reproduces the recorded motion exactly")
                Widgets.doubleSlider("##smooth", settings.smoothing, 0.0, 1.0)?.let { settings.smoothing = it }
                Widgets.property("Eye height")
                Widgets.doubleSlider("##eye", settings.eyeHeight, 0.0, 2.5)?.let { settings.eyeHeight = it }
            }
            Widgets.endProperties()
        }
        if (settings.interpolation == PoseInterpolation.SNAP && settings.exactFirstPerson) Widgets.smallText(
            "Snap interpolation disables frame blending.",
            EditorTheme.WARNING.u32
        )
    }

    private fun orbitSettings(settings: CameraSettings, control: CameraControl) {
        if (Widgets.beginProperties("orbit")) {
            Widgets.property("Distance")
            Widgets.doubleSlider("##dist", settings.orbitDistance, 0.5, 40.0, "%.1f")
                ?.let { settings.orbitDistance = it; control.apply() }
            Widgets.property("Pitch")
            Widgets.doubleSlider("##pitch", settings.orbitPitch, -89.0, 89.0, "%.0f°")
                ?.let { settings.orbitPitch = it; control.apply() }
            Widgets.property("Yaw offset")
            Widgets.doubleSlider("##yaw", settings.orbitYawOffset, -180.0, 180.0, "%.0f°")
                ?.let { settings.orbitYawOffset = it; control.apply() }
            Widgets.property("Height")
            Widgets.doubleSlider("##height", settings.orbitHeight, -5.0, 10.0, "%.1f")
                ?.let { settings.orbitHeight = it; control.apply() }
            Widgets.property("Auto spin")
            Widgets.doubleSlider("##spin", settings.orbitDegreesPerSecond, -180.0, 180.0, "%.0f°/s")
                ?.let { settings.orbitDegreesPerSecond = it; control.apply() }
            Widgets.endProperties()
        }
    }

    private fun followSettings(settings: CameraSettings, control: CameraControl) {
        if (Widgets.beginProperties("follow")) {
            Widgets.property("Offset", "Right, up and forward relative to the target's facing")
            Widgets.vector3("##offset", settings.followOffsetX, settings.followOffsetY, settings.followOffsetZ, 0.05f)
                ?.let {
                    settings.followOffsetX = it[0]
                    settings.followOffsetY = it[1]
                    settings.followOffsetZ = it[2]
                    control.apply()
                }
            Widgets.property("Look at target")
            Widgets.toggle("##lookat", settings.followLookAtTarget)
                ?.let { settings.followLookAtTarget = it; control.apply() }
            Widgets.endProperties()
        }
    }

    private fun chaseSettings(settings: CameraSettings, control: CameraControl) {
        if (Widgets.beginProperties("chase")) {
            Widgets.property("Distance")
            Widgets.doubleSlider("##dist", settings.chaseDistance, 0.5, 40.0, "%.1f")
                ?.let { settings.chaseDistance = it; control.apply() }
            Widgets.property("Height")
            Widgets.doubleSlider("##height", settings.chaseHeight, -5.0, 10.0, "%.1f")
                ?.let { settings.chaseHeight = it; control.apply() }
            Widgets.property("Stiffness")
            Widgets.doubleSlider("##stiff", settings.chaseStiffness, 1.0, 200.0, "%.0f")
                ?.let { settings.chaseStiffness = it; control.apply() }
            Widgets.property("Damping")
            Widgets.doubleSlider("##damp", settings.chaseDamping, 0.0, 40.0, "%.1f")
                ?.let { settings.chaseDamping = it; control.apply() }
            Widgets.endProperties()
        }
    }

    private fun pathSection(session: EditorSession, control: CameraControl) {
        val settings = control.settings
        val camera = session.project.camera
        val lane = session.project.lane(LaneKind.CAMERA)
        val state = when {
            control.pathActive -> "Following"
            lane.muted -> "Disabled"
            camera.isEmpty -> "Empty"
            else -> "Outside ${TimeFormat.short(camera.startNanos)} to ${TimeFormat.short(camera.endNanos)}"
        }
        if (!section("Camera path", "camera.path", trailing = state)) return
        if (Widgets.accentButton("Add keyframe")) session.keyframeAtPlayhead(control.currentPose())
        Widgets.tooltip("Record the current view as a keyframe at the playhead  Ctrl+K")
        ImGui.sameLine()
        if (Widgets.iconButton("kf-prev", Icon.CHEVRON_LEFT, ImGui.getFrameHeight(), "Previous keyframe  ,")) jump(
            session,
            -1
        )
        ImGui.sameLine()
        if (Widgets.iconButton("kf-next", Icon.CHEVRON_RIGHT, ImGui.getFrameHeight(), "Next keyframe  .")) jump(
            session,
            1
        )
        if (!camera.isEmpty) {
            ImGui.sameLine()
            if (Widgets.iconButton(
                    "kf-clear",
                    Icon.TRASH,
                    ImGui.getFrameHeight(),
                    "Clear the whole path",
                    color = EditorTheme.RECORD.u32
                )
            ) {
                session.execute(ClearCameraPath())
                session.selection = Selection.NONE
            }
        }
        if (settings.mode != CameraMode.FREE) {
            if (Widgets.ghostButton("Bake ${settings.mode.label.lowercase()} view to keyframes")) bakeView(
                session,
                control
            )
            Widgets.tooltip("Samples what this view mode shows across the in/out range into path keyframes you can then shape in the Graph Editor")
        }
        if (Widgets.beginProperties("path")) {
            Widgets.property("Enabled")
            Widgets.toggle("##enabled", !lane.muted)
                ?.let { session.execute(SetLaneState(LaneKind.CAMERA, lane.copy(muted = !it))) }
            Widgets.property("Keyframes")
            Widgets.mutedText(
                if (camera.isEmpty) "None" else "${camera.keyframeTimes().size} over ${
                    TimeFormat.short(
                        camera.durationNanos
                    )
                }"
            )
            Widgets.property("New keyframes", "Interpolation for keyframes you add next")
            val modes = SegmentMode.entries
            Widgets.segmented("defmode", modes.map { it.label }, modes.indexOf(session.defaultKeyframeMode), 0f)
                ?.let { session.defaultKeyframeMode = modes[it] }
            Widgets.property(
                "New easing",
                "Easing given to keyframes you add next; change it later per keyframe or in the Graph Editor"
            )
            EasingWidgets.picker("defeasing", session.defaultEasing)?.let { session.defaultEasing = it }
            Widgets.property("Before start", "What the path does before its first keyframe")
            Widgets.enumCombo("##pre", camera.preExtrapolation) { it.label }
                ?.let { session.execute(SetTrackExtrapolation(null, it, camera.postExtrapolation)) }
            Widgets.property(
                "After end",
                "What the path does after its last keyframe: loop it, ping pong, or keep flying"
            )
            Widgets.enumCombo("##post", camera.postExtrapolation) { it.label }
                ?.let { session.execute(SetTrackExtrapolation(null, camera.preExtrapolation, it)) }
            Widgets.property(
                "Auto key",
                "Moving the camera on a paused frame writes a keyframe at the playhead, like Unity's record mode"
            )
            Widgets.toggle("##autokey", session.autoKey)?.let { session.autoKey = it }
            Widgets.property("Aim at", "Keep the path camera pointed at an entity; only the look direction is replaced")
            aimPicker(session)
            Widgets.property("Path detail", "Smaller is smoother (and costlier); larger simplifies straight stretches")
            Widgets.doubleSlider("##tolerance", settings.pathToleranceBlocks, 0.01, 0.5, "%.2f blocks")
                ?.let { settings.pathToleranceBlocks = it }
            Widgets.endProperties()
        }
    }

    private fun bakeView(session: EditorSession, control: CameraControl) {
        val replay = session.replay ?: return
        val project = session.project
        val from = project.inPointNanos.coerceIn(0L, replay.durationNanos)
        val to = (if (project.outPointNanos > 0L) project.outPointNanos else replay.durationNanos).coerceIn(
            from,
            replay.durationNanos
        )
        if (to - from < BAKE_STEP) {
            context.status("Set an in and out range to bake")
            return
        }
        val poses = control.bakePoses(from, to, BAKE_STEP)
        if (poses.size < 2) {
            context.status("Nothing to bake in this view mode")
            return
        }
        session.execute(
            ReplaceCameraPath(
                PathTools.fromPoses(poses, from, BAKE_STEP, session.defaultEasing),
                "Bake view to path"
            )
        )
        val lane = project.lane(LaneKind.CAMERA)
        if (lane.muted) session.execute(SetLaneState(LaneKind.CAMERA, lane.copy(muted = false)))
        control.settings.mode = CameraMode.FREE
        control.apply()
        session.selection = Selection.NONE
        context.status("Baked ${poses.size} keyframes; simplify from the Camera track menu if you like")
    }

    private fun presets(session: EditorSession) {
        val store = PathPresets(
            context.host.projectsDirectory.resolve(
                session.project.recording.fileName.toString().substringBeforeLast('.') + ".paths"
            )
        )
        if (presetNames == null || presetsDirty) {
            presetNames = store.list()
            presetsDirty = false
        }
        val names = presetNames ?: emptyList()
        if (!Widgets.foldout("Saved paths", "camera.paths", context.ui.collapsedSections, false, if (names.isEmpty()) null else "${names.size}")) return
        val saveWidth = Widgets.buttonWidth("Save##path", Widgets.ButtonStyle.ACCENT)
        ImGui.setNextItemWidth(-(saveWidth + ImGui.getStyle().itemSpacingX))
        if (ImGui.inputTextWithHint(
                "##presetname",
                "Name",
                presetName,
                ImGuiInputTextFlags.EnterReturnsTrue
            )
        ) savePreset(session, store)
        ImGui.sameLine()
        val canSave = !session.project.camera.isEmpty && presetName.get().isNotBlank()
        if (!canSave) ImGui.beginDisabled()
        if (Widgets.accentButton("Save##path")) savePreset(session, store)
        if (!canSave) ImGui.endDisabled()
        if (names.isEmpty()) {
            Widgets.smallText("No saved paths yet.", EditorTheme.TEXT_DIM.u32)
            return
        }
        ImGui.dummy(0f, EditorFonts.px(2f))
        val rowHeight = EditorFonts.px(26f)
        val button = EditorFonts.px(22f)
        for (name in names) {
            ImGui.pushID(name)
            try {
                val top = ImGui.getCursorScreenPosY()
                val width = ImGui.getContentRegionAvailX()
                val clicked = Widgets.row("preset", rowHeight, false) { x, y, _, _ ->
                    val list = ImGui.getWindowDrawList()
                    val iconSize = EditorFonts.px(14f)
                    Icons.draw(list, Icon.PATH, x + EditorFonts.px(8f), y + (rowHeight - iconSize) / 2f, iconSize, EditorTheme.TEXT_MUTED.u32)
                    list.addText(
                        x + EditorFonts.px(30f),
                        y + (rowHeight - ImGui.getFontSize()) / 2f,
                        EditorTheme.TEXT.u32,
                        Widgets.clip(name, width - EditorFonts.px(30f) - button * 2f - EditorFonts.px(16f))
                    )
                }
                if (clicked) loadPreset(session, store, name)
                val hovered = ImGui.isItemHovered()
                ImGui.setCursorScreenPos(ImGui.getCursorScreenPosX() + width - button * 2f - EditorFonts.px(8f), top + (rowHeight - button) / 2f)
                if (hovered || ImGui.isMouseHoveringRect(ImGui.getCursorScreenPosX(), top, ImGui.getCursorScreenPosX() + button * 2f + EditorFonts.px(4f), top + rowHeight)) {
                    if (Widgets.iconButton("load", Icon.FOLDER, button, "Load this path", iconScale = 0.55f, color = EditorTheme.TEXT_MUTED.u32)) loadPreset(session, store, name)
                    ImGui.sameLine(0f, EditorFonts.px(4f))
                    if (Widgets.iconButton("delete", Icon.TRASH, button, "Delete this saved path", iconScale = 0.55f, color = EditorTheme.RECORD.u32)) {
                        store.delete(name)
                        presetsDirty = true
                    }
                }
                ImGui.setCursorScreenPos(ImGui.getCursorScreenPosX(), top + rowHeight + ImGui.getStyle().itemSpacingY)
            } finally {
                ImGui.popID()
            }
        }
    }

    private fun loadPreset(session: EditorSession, store: PathPresets, name: String) {
        val loaded = store.load(name) ?: return
        session.execute(ReplaceCameraPath(loaded, "Load path '$name'"))
        session.selection = Selection.NONE
        context.status("Loaded path $name")
    }

    private fun section(title: String, key: String, trailing: String? = null): Boolean =
        Widgets.foldout(title, key, context.ui.collapsedSections, true, trailing)

    private fun savePreset(session: EditorSession, store: PathPresets) {
        val name = presetName.get().trim()
        if (name.isEmpty() || session.project.camera.isEmpty) return
        store.save(name, session.project.camera)
        presetsDirty = true
        context.status("Saved path $name")
    }

    private fun jump(session: EditorSession, direction: Int) {
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

    private fun entityLabel(id: Int): String {
        val shadow = context.replay?.world ?: return "#$id"
        val entity = shadow.entities[id] ?: return "#$id"
        val name = entity.uuid?.let { shadow.players.profile(it)?.name }
        return name ?: "${entity.kind.name.lowercase().replace('_', ' ')} #$id"
    }

    private companion object {
        val BAKE_STEP: Long = Nanos.ofMillis(250)
        val MODE_TOOLTIPS = listOf(
            "Fly freely. Right drag looks, WASD flies, wheel dollies.",
            "See through the target's eyes. The recorder's view is frame exact.",
            "Circle around the target at a fixed distance.",
            "Stay at a fixed offset from the target.",
            "Trail behind the target with a spring.",
        )
    }
}
