package gg.sona.afterimage.editor.imgui

import gg.sona.afterimage.camera.track.SegmentMode
import gg.sona.afterimage.clip.Clip
import gg.sona.afterimage.core.time.Nanos
import gg.sona.afterimage.editor.*
import gg.sona.afterimage.editor.commands.*
import gg.sona.afterimage.replay.session.ReplaySession
import imgui.ImGui
import imgui.flag.ImGuiCol
import imgui.flag.ImGuiInputTextFlags
import imgui.type.ImString

class TimelineMenus(
    private val context: EditorContext,
    private val actions: TimelineActions,
    private val cache: TimelineCache,
    private val lanes: TimelineLanes,
    private val gameLanes: GameLanes,
) {
    private val renameBuffer = ImString("", 64)
    private val valueHolder = FloatArray(1)

    fun prepare(session: EditorSession, item: TimelineItem?) {
        when (item) {
            is TimelineItem.MarkerItem -> session.project.marker(item.id)?.let { renameBuffer.set(it.label) }
            is TimelineItem.ValueKeyItem -> session.project.valueTrack(item.key.lane).at(item.key.nanos)
                ?.let { valueHolder[0] = it.value.toFloat() }

            is TimelineItem.MomentItem -> gameLanes.moments(session).firstOrNull { it.id == item.id }
                ?.let { gameLanes.beginMomentMenu(it) }

            else -> Unit
        }
    }

    fun item(session: EditorSession, item: TimelineItem) {
        val replay = session.replay ?: return
        val project = session.project
        when (item) {
            is TimelineItem.CameraKey -> keyframe(session, replay, item.nanos)
            is TimelineItem.ValueKeyItem -> value(session, item.key)
            is TimelineItem.ViewKey -> view(session, replay, item.nanos)
            is TimelineItem.PackKey -> pack(session, replay, item.nanos)
            is TimelineItem.ClipItem -> project.clip(item.id)?.let { clip(session, replay, it) }
            is TimelineItem.MarkerItem -> project.marker(item.id)?.let { marker(session, replay, it) }
            is TimelineItem.TimelapseItem -> project.timelapse(item.id)?.let { timelapse(session, replay, it) }
            is TimelineItem.MomentItem -> gameLanes.moments(session).firstOrNull { it.id == item.id }
                ?.let { gameLanes.momentMenu(session, it) }
        }
    }

    private fun keyframe(session: EditorSession, replay: ReplaySession, time: Long) {
        val frame = session.project.camera.keyframeAt(time) ?: return
        val targets = session.selection.keyframeTimes.ifEmpty { setOf(time) }
        Widgets.mutedText("Keyframe ${TimeFormat.clock(time)}${if (targets.size > 1) "   ${targets.size} selected" else ""}")
        ImGui.separator()
        if (Menus.item("Go to keyframe")) replay.seek(time)
        if (Menus.item("Update from current view")) session.execute(
            SetCameraKeyframe(time, context.host.camera.currentPose(), frame.easing, frame.mode)
        )
        if (Menus.item("Duplicate at playhead", "Ctrl+D")) {
            session.execute(SetCameraKeyframe(replay.positionNanos, frame.pose, frame.easing, frame.mode))
            session.selection = Selection(keyframeTimes = setOf(replay.positionNanos))
        }
        ImGui.separator()
        if (ImGui.beginMenu("Interpolation")) {
            for (mode in SegmentMode.entries) {
                if (Menus.item(mode.label, "", frame.mode == mode)) session.execute(SetKeyframeMode(targets, mode))
            }
            ImGui.endMenu()
        }
        if (ImGui.beginMenu("Easing")) {
            EasingWidgets.menu(frame.easing)?.let { session.execute(SetKeyframeEasing(targets, it)) }
            ImGui.endMenu()
        }
        if (Menus.item("Easy ease", "F9")) session.execute(EaseKeyframes.easyEase(targets, emptySet()))
        if (Menus.item("Ease in", "Shift+F9")) session.execute(EaseKeyframes.easeIn(targets, emptySet()))
        if (Menus.item("Ease out", "Ctrl+Shift+F9")) session.execute(EaseKeyframes.easeOut(targets, emptySet()))
        if (Menus.item("Edit curves in Graph Editor", "Ctrl+G")) context.openPanel("Graph Editor")
        ImGui.separator()
        if (Menus.item(if (targets.size > 1) "Delete ${targets.size} keyframes" else "Delete keyframe", "Del")) {
            session.execute(RemoveKeyframes(targets))
            session.selection = Selection.NONE
        }
    }

    private fun value(session: EditorSession, key: ValueKey) {
        val lane = key.lane
        val frame = session.project.valueTrack(lane).at(key.nanos) ?: return
        Widgets.mutedText("${lane.label} keyframe ${TimeFormat.clock(key.nanos)}")
        ImGui.separator()
        Widgets.slider(
            "##value",
            valueHolder[0],
            lane.min.toFloat(),
            lane.max.toFloat(),
            lane.format,
            EditorFonts.px(210f),
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
        if (Menus.item("Go to keyframe")) session.replay?.seek(key.nanos)
        if (ImGui.beginMenu("Ramp")) {
            for (mode in listOf(SegmentMode.LINEAR, SegmentMode.CATMULL_ROM, SegmentMode.HOLD)) {
                if (Menus.item(mode.label, "", frame.mode == mode)) session.execute(
                    SetValueKeyframe(lane, key.nanos, frame.value, mode, frame.easing)
                )
            }
            ImGui.endMenu()
        }
        if (ImGui.beginMenu("Easing")) {
            EasingWidgets.menu(frame.easing)?.let { session.execute(SetValueKeyframeEasing(setOf(key), it)) }
            ImGui.endMenu()
        }
        if (Menus.item("Easy ease", "F9")) session.execute(EaseKeyframes.easyEase(emptySet(), setOf(key)))
        if (Menus.item("Edit curves in Graph Editor", "Ctrl+G")) context.openPanel("Graph Editor")
        ImGui.separator()
        if (Menus.item("Delete keyframe", "Del")) {
            session.execute(RemoveValueKeyframes(setOf(key)))
            session.selection = Selection.NONE
        }
    }

    private fun view(session: EditorSession, replay: ReplaySession, time: Long) {
        val frame = session.project.views.at(time) ?: return
        val targets = session.selection.viewTimes.ifEmpty { setOf(time) }
        Widgets.mutedText("${lanes.viewLabel(frame.value)} from ${TimeFormat.clock(time)}")
        ImGui.separator()
        if (Menus.item("Go to")) replay.seek(time)
        if (Menus.item("Update from current camera settings")) session.execute(
            SetViewKeyframe(time, ViewState.capture(context.host.camera.settings), frame.mode, frame.easing)
        )
        if (Menus.item("Apply to camera now")) {
            frame.value.applyTo(context.host.camera.settings)
            context.host.camera.apply()
        }
        ImGui.separator()
        if (ImGui.beginMenu("Interpolation")) {
            for (mode in SegmentMode.entries) {
                if (Menus.item(mode.label, "", frame.mode == mode)) session.execute(SetViewKeyframeMode(targets, mode))
            }
            ImGui.endMenu()
        }
        Widgets.tooltip("Hold snaps to this keyframe's camera mode, target and settings until the next one. Smooth blends orbit, follow and chase numbers into the next keyframe when it shares the same mode and target.")
        ImGui.separator()
        if (Menus.item("Delete view keyframe", "Del")) {
            session.execute(RemoveViewKeyframes(setOf(time)))
            session.selection = Selection.NONE
        }
    }

    private fun pack(session: EditorSession, replay: ReplaySession, time: Long) {
        val frame = session.project.packs.at(time) ?: return
        Widgets.mutedText("${lanes.packLabel(frame.value)} from ${TimeFormat.clock(time)}")
        ImGui.separator()
        if (Menus.item("Go to")) replay.seek(time)
        ImGui.separator()
        if (Menus.item("Default textures", "", frame.value.isDefault)) session.execute(SetPackKeyframe(time, PackState.DEFAULT))
        val available = context.host.resourcePacks()
        if (available.isEmpty()) Widgets.mutedText("No packs in the resourcepacks folder")
        for (name in available) {
            if (Menus.item(name.removeSuffix(".zip"), "", name in frame.value.packs)) session.execute(
                SetPackKeyframe(time, frame.value.toggled(name))
            )
        }
        Widgets.tooltip("Packs listed later override earlier ones, like the in-game resource pack screen. Switching packs reloads textures, which takes a moment.")
        ImGui.separator()
        if (Menus.item("Delete texture pack keyframe", "Del")) {
            session.execute(RemovePackKeyframes(setOf(time)))
            session.selection = Selection.NONE
        }
    }

    private fun clip(session: EditorSession, replay: ReplaySession, clip: Clip) {
        Widgets.mutedText(clip.title)
        ImGui.separator()
        if (Menus.item("Play clip")) actions.playClip(session, clip)
        if (Menus.item("Set in and out to clip")) session.execute(SetInOutPoints(clip.startNanos, clip.endNanos))
        if (Menus.item("Go to start")) replay.seek(clip.startNanos)
        if (Menus.item("Open in Clips")) context.openPanel("Clips")
        ImGui.separator()
        if (Menus.item("Delete clip", "Del")) {
            session.execute(RemoveClip(clip.id))
            context.clips.delete(clip.id)
            session.selection = Selection.NONE
        }
    }

    private fun marker(session: EditorSession, replay: ReplaySession, marker: TimelineMarker) {
        Widgets.mutedText("Marker ${TimeFormat.clock(marker.nanos)}")
        ImGui.separator()
        ImGui.setNextItemWidth(EditorFonts.px(190f))
        if (ImGui.inputText("##rename", renameBuffer, ImGuiInputTextFlags.EnterReturnsTrue)) {
            session.execute(ReplaceMarker(marker.id, marker.copy(label = renameBuffer.get())))
            ImGui.closeCurrentPopup()
        }
        for ((index, color) in TimelineActions.MARKER_COLORS.withIndex()) {
            if (index > 0) ImGui.sameLine()
            if (Widgets.colorSwatch("color$index", color, selected = marker.color == color)) session.execute(
                ReplaceMarker(marker.id, marker.copy(color = color))
            )
        }
        if (ImGui.beginMenu("Type")) {
            for (kind in MarkerKind.entries) if (Menus.item(kind.label, "", marker.kind == kind)) session.execute(
                ReplaceMarker(marker.id, marker.copy(kind = kind, color = if (marker.kind == kind) marker.color else kind.color))
            )
            ImGui.endMenu()
        }
        ImGui.separator()
        if (Menus.item("Go to marker")) replay.seek(marker.nanos)
        if (Menus.item("Move to playhead")) session.execute(ReplaceMarker(marker.id, marker.copy(nanos = replay.positionNanos)))
        if (Menus.item("Delete marker", "Del")) {
            session.execute(RemoveMarker(marker.id))
            session.selection = Selection.NONE
        }
    }

    private fun timelapse(session: EditorSession, replay: ReplaySession, mark: TimelapseMark) {
        Widgets.mutedText("Timelapse ${TimeFormat.clock(mark.nanos)}")
        ImGui.separator()
        ImGui.setNextItemWidth(EditorFonts.px(170f))
        Widgets.doubleSlider("##skip", mark.skipNanos / Nanos.PER_SECOND.toDouble(), 0.1, 300.0, "+%.1f s")?.let {
            session.execute(ReplaceTimelapse(mark.id, mark.copy(skipNanos = (it * Nanos.PER_SECOND).toLong())))
        }
        ImGui.separator()
        if (Menus.item("Go to")) replay.seek(mark.nanos)
        if (Menus.item("Delete timelapse", "Del")) {
            session.execute(RemoveTimelapse(mark.id))
            session.selection = Selection.NONE
        }
    }

    fun empty(session: EditorSession, nanos: Long, lane: TrackSpec?, visible: List<TrackSpec>) {
        val replay = session.replay ?: return
        Widgets.mutedText(TimeFormat.clock(nanos))
        ImGui.separator()
        if (Menus.item("Jump here")) replay.seek(nanos)
        if (Menus.item("Add camera keyframe here", "Ctrl+K")) {
            replay.seek(nanos)
            actions.addCameraKeyframe(session, nanos)
        }
        val valueLane = lane?.valueLane
        when {
            valueLane != null -> if (Menus.item("Add ${valueLane.label.lowercase()} keyframe here")) actions.addValueKeyframe(session, valueLane, nanos)
            lane?.kind == LaneKind.VIEW -> if (Menus.item("Add view keyframe here")) actions.addViewKeyframe(session, nanos)
            lane?.kind == LaneKind.TEXTURE_PACK -> if (Menus.item("Add texture pack keyframe here")) actions.addPackKeyframe(session, nanos)
            lane?.kind == LaneKind.TIMELAPSE -> if (Menus.item("Add timelapse skip here")) actions.addTimelapse(session, nanos)
            lane?.kind == LaneKind.MOMENTS -> if (Menus.item("Add moment here")) gameLanes.addManual(session, nanos)
        }
        if (Menus.item("Add marker here", "M")) actions.addMarker(session, nanos)
        if (ImGui.beginMenu("Add keyframe")) {
            for (value in ValueLane.entries) if (Menus.item(value.label)) actions.addValueKeyframe(session, value, nanos)
            ImGui.separator()
            if (Menus.item("View")) actions.addViewKeyframe(session, nanos)
            if (Menus.item("Texture pack")) actions.addPackKeyframe(session, nanos)
            if (Menus.item("Timelapse skip")) actions.addTimelapse(session, nanos)
            ImGui.endMenu()
        }
        ImGui.separator()
        if (Menus.item("Set in point here", "I")) actions.setInPoint(session, nanos)
        if (Menus.item("Set out point here", "O")) actions.setOutPoint(session, nanos)
        if (Menus.item("Clip from in and out")) actions.clipFromInOut(session)
        ImGui.separator()
        if (Menus.item("Select all", "Ctrl+A")) actions.selectAll(session, cache, visible)
        if (Menus.item("Delete selection", "Del", false, !session.selection.isEmpty)) actions.deleteSelection(session)
    }

    fun track(session: EditorSession, view: TimelineView, spec: TrackSpec) {
        val kind = spec.kind
        val state = session.project.lane(kind)
        val playhead = session.playheadNanos
        Widgets.mutedText("${spec.label} track")
        ImGui.separator()
        when (kind) {
            LaneKind.CAMERA -> {
                if (Menus.item("Add keyframe at playhead", "Ctrl+K")) actions.addCameraKeyframe(session, playhead)
                if (Menus.item(if (state.muted) "Enable camera path" else "Disable camera path")) session.execute(
                    SetLaneState(kind, state.copy(muted = !state.muted))
                )
                if (Menus.item(if (state.locked) "Unlock" else "Lock")) session.execute(SetLaneState(kind, state.copy(locked = !state.locked)))
                if (Menus.item("Select all keyframes", "", false, cache.keyTimes.isNotEmpty())) session.selection =
                    Selection(keyframeTimes = cache.keyTimes.toSet())
                pathTools(session)
                ImGui.separator()
                if (Menus.item("Clear camera path", "", false, cache.keyTimes.isNotEmpty())) {
                    session.execute(ClearCameraPath())
                    session.selection = Selection.NONE
                }
            }

            LaneKind.VIEW -> {
                if (Menus.item("Add view keyframe at playhead")) actions.addViewKeyframe(session, playhead)
                mute(session, kind, state)
                hide(view, kind, cache.viewKeys.isEmpty()) {
                    session.execute(RemoveViewKeyframes(cache.viewKeys.map { it.timeNanos }.toSet()))
                }
            }

            LaneKind.TEXTURE_PACK -> {
                if (Menus.item("Add texture pack keyframe at playhead")) actions.addPackKeyframe(session, playhead)
                mute(session, kind, state)
                hide(view, kind, cache.packKeys.isEmpty()) {
                    session.execute(RemovePackKeyframes(cache.packKeys.map { it.timeNanos }.toSet()))
                }
            }

            LaneKind.TIMELAPSE -> {
                if (Menus.item("Add timelapse skip at playhead")) actions.addTimelapse(session, playhead)
                mute(session, kind, state)
                hide(view, kind, cache.timelapses.isEmpty()) {
                    val commands = cache.timelapses.map { RemoveTimelapse(it.id) }
                    session.execute(CompoundCommand("Clear timelapse skips", commands))
                }
            }

            LaneKind.CLIPS -> {
                if (Menus.item("Clip from in and out")) actions.clipFromInOut(session)
                if (Menus.item("Select all clips", "", false, cache.clips.isNotEmpty())) session.selection =
                    Selection(clipIds = cache.clips.map { it.id }.toSet())
                if (Menus.item("Open Clips panel")) context.openPanel("Clips")
                if (cache.clips.isEmpty()) {
                    ImGui.separator()
                    if (Menus.item("Hide track")) view.shownLanes.remove(kind)
                }
            }

            LaneKind.MARKERS -> {
                if (Menus.item("Add marker at playhead", "M")) actions.addMarker(session, playhead)
                if (Menus.item("Select all markers", "", false, cache.markers.isNotEmpty())) session.selection =
                    Selection(markerIds = cache.markers.map { it.id }.toSet())
                hide(view, kind, cache.markers.isEmpty()) {
                    session.execute(CompoundCommand("Clear markers", cache.markers.map { RemoveMarker(it.id) }))
                }
            }

            LaneKind.EVENTS -> remove(view, kind)
            LaneKind.PLAYERS -> {
                gameLanes.playersLaneMenu()
                remove(view, kind)
            }

            LaneKind.WORLD -> {
                gameLanes.worldLaneMenu()
                remove(view, kind)
            }

            LaneKind.MOMENTS -> {
                gameLanes.momentsLaneMenu(session)
                remove(view, kind)
            }

            else -> {
                val valueLane = spec.valueLane ?: return
                val keys = cache.values(valueLane)
                if (Menus.item("Add keyframe at playhead")) actions.addValueKeyframe(session, valueLane, playhead)
                mute(session, kind, state)
                if (Menus.item("Select all keyframes", "", false, keys.isNotEmpty())) session.selection =
                    Selection(valueKeys = keys.map { ValueKey(valueLane, it.timeNanos) }.toSet())
                if (Menus.item("Edit curve in Graph Editor", "Ctrl+G")) context.openPanel("Graph Editor")
                hide(view, kind, keys.isEmpty()) {
                    session.execute(RemoveValueKeyframes(keys.map { ValueKey(valueLane, it.timeNanos) }.toSet()))
                }
            }
        }
        ImGui.separator()
        Widgets.smallText(spec.hint, EditorTheme.TEXT_DIM.u32)
    }

    private fun mute(session: EditorSession, kind: LaneKind, state: LaneState) {
        if (Menus.item(if (state.muted) "Enable" else "Disable")) session.execute(SetLaneState(kind, state.copy(muted = !state.muted)))
    }

    private fun hide(view: TimelineView, kind: LaneKind, empty: Boolean, clear: () -> Unit) {
        ImGui.separator()
        if (empty) {
            if (Menus.item("Hide track")) view.shownLanes.remove(kind)
        } else if (Menus.item("Clear and hide track")) {
            clear()
            context.session?.selection = Selection.NONE
            view.shownLanes.remove(kind)
        }
    }

    private fun remove(view: TimelineView, kind: LaneKind) {
        ImGui.separator()
        if (Menus.item("Remove track")) view.shownLanes.remove(kind)
    }

    private fun pathTools(session: EditorSession) {
        if (!ImGui.beginMenu("Path tools", cache.keyTimes.size >= 2)) return
        val path = session.project.camera
        if (Menus.item("Reverse direction")) session.execute(ReplaceCameraPath(PathTools.reversed(path), "Reverse camera path"))
        Widgets.tooltip("Play the same path backwards: the last keyframe becomes the first")
        if (Menus.item("Fit to in and out")) session.execute(
            ReplaceCameraPath(PathTools.retimed(path, actions.inPoint(session), actions.outPoint(session)), "Fit camera path to in and out")
        )
        Widgets.tooltip("Stretch or squeeze the keyframes so the path starts at the in point and ends at the out point")
        if (Menus.item("Start at playhead")) session.execute(
            ReplaceCameraPath(PathTools.shifted(path, session.playheadNanos - cache.keyTimes.first()), "Shift camera path")
        )
        if (Menus.item("Space keyframes evenly")) session.execute(ReplaceCameraPath(PathTools.evenlySpaced(path), "Space keyframes evenly"))
        if (Menus.item("Time by distance (constant speed)")) session.execute(
            ReplaceCameraPath(PathTools.byDistance(path), "Retime camera path by distance")
        )
        Widgets.tooltip("Re-times keyframes so the camera moves at a constant speed along the path")
        ImGui.separator()
        if (Menus.item("Simplify (remove redundant keyframes)")) session.execute(
            ReplaceCameraPath(PathTools.simplified(path, 0.35), "Simplify camera path")
        )
        Widgets.tooltip("Drops keyframes that sit within a third of a block of the straight line between their neighbours; great after recording a flight")
        if (Menus.item("Mirror east-west")) session.execute(ReplaceCameraPath(PathTools.mirrored(path, 0), "Mirror camera path"))
        if (Menus.item("Mirror north-south")) session.execute(ReplaceCameraPath(PathTools.mirrored(path, 2), "Mirror camera path"))
        ImGui.separator()
        if (Menus.item("Face direction of travel")) session.execute(
            ReplaceCameraPath(PathTools.facingTravel(path), "Face camera path along travel")
        )
        Widgets.tooltip("Points every keyframe toward the next one, like a fly-through")
        if (Menus.item("Level the horizon (roll 0)")) session.execute(ReplaceCameraPath(PathTools.levelled(path), "Level camera path"))
        if (Menus.item("Use current FOV everywhere")) session.execute(
            ReplaceCameraPath(PathTools.withUniformFov(path, context.host.camera.currentPose().fov), "Set camera path FOV")
        )
        ImGui.separator()
        for (mode in SegmentMode.entries) {
            if (Menus.item("All ${mode.label.lowercase()}")) session.execute(
                ReplaceCameraPath(PathTools.withUniformMode(path, mode), "Set all keyframes to ${mode.label.lowercase()}")
            )
        }
        ImGui.endMenu()
    }

    fun addTrack(view: TimelineView, hidden: List<TrackSpec>) {
        Widgets.mutedText("Add track")
        var group: TrackGroup? = null
        for (spec in hidden) {
            if (spec.group != group) {
                ImGui.separator()
                EditorFonts.with(EditorFonts.label) {
                    ImGui.pushStyleColor(ImGuiCol.Text, EditorTheme.TEXT_DIM.u32)
                    ImGui.textUnformatted(spec.group.label.uppercase())
                    ImGui.popStyleColor()
                }
                group = spec.group
            }
            if (Menus.item(spec.label)) view.shownLanes.add(spec.kind)
            Widgets.tooltip(spec.hint)
        }
    }

    companion object {
        val VALUE_PRESETS = mapOf(
            ValueLane.SPEED to doubleArrayOf(0.25, 0.5, 1.0, 2.0, 4.0),
            ValueLane.FOV to doubleArrayOf(30.0, 50.0, 70.0, 90.0, 110.0),
            ValueLane.TIME_OF_DAY to doubleArrayOf(0.0, 6000.0, 12000.0, 18000.0),
            ValueLane.SHAKE to doubleArrayOf(0.0, 0.5, 1.0, 2.0),
            ValueLane.FREEZE to doubleArrayOf(0.5, 1.0, 2.0, 5.0),
            ValueLane.SHAKE_FREQUENCY to doubleArrayOf(0.5, 1.6, 4.0, 10.0),
            ValueLane.FOCUS to doubleArrayOf(2.0, 4.0, 8.0, 16.0, 32.0),
        )
    }
}
