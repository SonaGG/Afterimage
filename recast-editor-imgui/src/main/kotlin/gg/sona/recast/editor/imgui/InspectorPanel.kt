package gg.sona.recast.editor.imgui

import gg.sona.recast.camera.*
import gg.sona.recast.camera.track.SegmentMode
import gg.sona.recast.clip.Clip
import gg.sona.recast.clip.ClipOrigin
import gg.sona.recast.core.time.Nanos
import gg.sona.recast.editor.*
import gg.sona.recast.editor.commands.*
import gg.sona.recast.editor.host.RecordingInfo
import gg.sona.recast.editor.pose.BodyPart
import gg.sona.recast.editor.pose.PartPose
import imgui.ImGui
import imgui.flag.ImGuiCol
import imgui.flag.ImGuiInputTextFlags
import org.joml.Vector3d
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.*
import kotlin.math.abs

class InspectorPanel(private val context: EditorContext) : AbstractPanel("Inspector", DockArea.RIGHT, Icon.SLIDERS) {

    private var infoPath: Path? = null
    private var info: RecordingInfo? = null

    override fun content(frame: FrameContext) {
        val session = context.session
        if (session == null) {
            Widgets.emptyState("Nothing to inspect", "Open a recording first", Icon.INFO)
            return
        }
        val selection = session.selection
        when {
            selection.keyframeTimes.size == 1 -> keyframe(session, selection.keyframeTimes.first())
            selection.keyframeTimes.size > 1 -> keyframes(session, selection.keyframeTimes)
            selection.valueKeys.isNotEmpty() -> valueKeyframe(session, selection.valueKeys.first())
            selection.viewTimes.isNotEmpty() -> viewKeyframe(session, selection.viewTimes.first())
            selection.clipIds.isNotEmpty() -> clip(session, selection.clipIds.first())
            selection.markerIds.isNotEmpty() -> marker(session, selection.markerIds.first())
            else -> when (val target = context.inspect) {
                is InspectTarget.Entity -> entity(session, target)
                is InspectTarget.Lane -> lane(session, target.kind)
                else -> project(session)
            }
        }
    }

    private fun title(
        icon: Icon,
        text: String,
        subtitle: String? = null,
        iconColor: Int = EditorTheme.ACCENT_TEXT.u32
    ) {
        val size = EditorFonts.px(28f)
        val x = ImGui.getCursorScreenPosX()
        val y = ImGui.getCursorScreenPosY()
        val list = ImGui.getWindowDrawList()
        list.addRectFilled(x, y, x + size, y + size, EditorTheme.CONTROL.u32, EditorFonts.px(7f))
        Icons.draw(list, icon, x + size * 0.22f, y + size * 0.22f, size * 0.56f, iconColor)
        ImGui.dummy(size, size)
        ImGui.sameLine(0f, EditorFonts.px(10f))
        ImGui.beginGroup()
        EditorFonts.with(EditorFonts.heading) { ImGui.textUnformatted(text) }
        if (subtitle != null) Widgets.smallText(subtitle, EditorTheme.TEXT_MUTED.u32)
        ImGui.endGroup()
        ImGui.dummy(0f, EditorFonts.px(6f))
    }

    private fun keyframe(session: EditorSession, time: Long) {
        val frame = session.project.camera.keyframeAt(time)
        if (frame == null) {
            session.selection = Selection.NONE
            return
        }
        val replay = session.replay
        val atPlayhead = replay != null && abs(replay.positionNanos - time) < Nanos.PER_MILLI * 5
        title(
            Icon.KEYFRAME,
            "Keyframe",
            "${TimeFormat.clock(time)}${if (atPlayhead) "    at playhead" else ""}",
            EditorTheme.modeColor(frame.mode).u32
        )

        if (Widgets.accentButton("Update from view")) session.execute(
            SetCameraKeyframe(
                time,
                context.host.camera.currentPose(),
                frame.easing,
                frame.mode
            )
        )
        Widgets.tooltip("Replace this keyframe with the current camera")
        ImGui.sameLine()
        if (Widgets.iconButton(
                "kf-goto",
                Icon.TARGET,
                ImGui.getFrameHeight(),
                "Go to this keyframe"
            )
        ) replay?.seek(time)
        ImGui.sameLine()
        if (Widgets.iconButton("kf-look", Icon.EYE, ImGui.getFrameHeight(), "Look through this keyframe")) lookThrough(
            frame.pose
        )
        ImGui.sameLine()
        if (Widgets.iconButton(
                "kf-frame",
                Icon.FIT,
                ImGui.getFrameHeight(),
                "Frame it in the scene  F"
            )
        ) context.host.camera.frame(frame.pose.position.x, frame.pose.position.y, frame.pose.position.z, 1.5)
        ImGui.sameLine()
        if (Widgets.iconButton(
                "kf-delete",
                Icon.TRASH,
                ImGui.getFrameHeight(),
                "Delete keyframe",
                color = EditorTheme.RECORD.u32
            )
        ) {
            session.execute(RemoveKeyframes(setOf(time)))
            session.selection = Selection.NONE
            return
        }

        Widgets.header("Timing")
        if (Widgets.beginProperties("timing")) {
            Widgets.property("Time")
            Widgets.textInput("##time", TimeFormat.clock(time), 32, ImGuiInputTextFlags.EnterReturnsTrue)?.let { text ->
                TimeFormat.parseClock(text)?.let { target ->
                    if (target != time && session.project.camera.keyframeAt(target) == null) {
                        session.execute(MoveCameraKeyframe(time, target))
                        session.selection = Selection(keyframeTimes = setOf(target))
                    }
                }
            }
            Widgets.property("Nudge")
            nudgeRow(session, setOf(time))
            Widgets.property("Interpolation", "How the camera moves from this keyframe to the next one")
            val modes = SegmentMode.entries
            Widgets.segmented("mode", modes.map { it.label }, modes.indexOf(frame.mode), 0f, MODE_TOOLTIPS)
                ?.let { session.execute(SetKeyframeMode(setOf(time), modes[it])) }
            Widgets.endProperties()
        }
        easingSection(
            session,
            "Easing to next keyframe",
            frame.easing,
            next = session.project.camera.nextKeyframeTime(time) != null,
            times = setOf(time),
            keys = emptySet()
        ) { session.execute(SetKeyframeEasing(setOf(time), it)) }

        Widgets.header("Transform")
        if (Widgets.beginProperties("transform")) {
            val pose = frame.pose
            Widgets.property("Position")
            Widgets.vector3("##pos", pose.position.x, pose.position.y, pose.position.z, 0.05f)?.let {
                session.execute(
                    SetCameraKeyframe(
                        time,
                        CameraPose(Vector3d(it[0], it[1], it[2]), pose.rotation, pose.fov),
                        frame.easing,
                        frame.mode
                    )
                )
            }
            Widgets.property("Rotation")
            Widgets.vector3("##rot", pose.rotation.yaw, pose.rotation.pitch, pose.rotation.roll, 0.5f, "%.1f°")?.let {
                session.execute(
                    SetCameraKeyframe(
                        time,
                        CameraPose(pose.position, Rotation(it[0], it[1].coerceIn(-90.0, 90.0), it[2]), pose.fov),
                        frame.easing,
                        frame.mode
                    )
                )
            }
            Widgets.property("Field of view")
            Widgets.doubleSlider("##fov", pose.fov, 10.0, 150.0, "%.0f°")?.let {
                session.execute(
                    SetCameraKeyframe(
                        time,
                        CameraPose(pose.position, pose.rotation, it),
                        frame.easing,
                        frame.mode
                    )
                )
            }
            Widgets.endProperties()
        }
        if (frame.mode == SegmentMode.BEZIER) bezierHandles(session, time)

        val previous = session.project.camera.previousKeyframeTime(time)
        val next = session.project.camera.nextKeyframeTime(time)
        Widgets.smallText(
            "${previous?.let { "Previous ${TimeFormat.short(it)}" } ?: "First keyframe"}    ${
                next?.let {
                    "Next ${
                        TimeFormat.short(
                            it
                        )
                    }"
                } ?: "Last keyframe"
            }",
            EditorTheme.TEXT_DIM.u32,
        )
    }

    private fun nudgeRow(session: EditorSession, times: Set<Long>) {
        val frameNanos = context.timeline.frameNanos()
        val width = (ImGui.getContentRegionAvailX() - EditorFonts.px(6f) * 3f) / 4f
        if (Widgets.button("-1 s", width)) nudge(session, times, -Nanos.PER_SECOND)
        ImGui.sameLine()
        if (Widgets.button("-1 f", width)) nudge(session, times, -frameNanos)
        ImGui.sameLine()
        if (Widgets.button("+1 f", width)) nudge(session, times, frameNanos)
        ImGui.sameLine()
        if (Widgets.button("+1 s", width)) nudge(session, times, Nanos.PER_SECOND)
    }

    private fun lookThrough(pose: CameraPose) {
        val control = context.host.camera
        if (control.settings.mode != CameraMode.FREE) {
            control.settings.mode = CameraMode.FREE
            control.apply()
        }
        control.teleport(pose)
    }

    private fun bezierHandles(session: EditorSession, time: Long) {
        val keyframe = session.project.camera.position.at(time) ?: return
        Widgets.header("Bezier handles")
        if (Widgets.beginProperties("bezier")) {
            val handleIn = keyframe.handleIn
            val handleOut = keyframe.handleOut
            Widgets.property("Custom handles", "Drag the purple handles in the scene once enabled")
            Widgets.toggle("##custom", handleIn != null || handleOut != null)?.let { enabled ->
                if (enabled) {
                    val previous = session.project.camera.position.previous(time)?.value ?: keyframe.value
                    val next = session.project.camera.position.next(time)?.value ?: keyframe.value
                    val tangent = Vector3d(next).sub(previous).mul(1.0 / 6.0)
                    session.execute(
                        SetPositionHandles(
                            time,
                            Vector3d(keyframe.value).sub(tangent),
                            Vector3d(keyframe.value).add(tangent)
                        )
                    )
                } else {
                    session.execute(SetPositionHandles(time, null, null))
                }
            }
            if (handleIn != null && handleOut != null) {
                Widgets.property("Handle in")
                Widgets.vector3("##hin", handleIn.x, handleIn.y, handleIn.z, 0.05f)
                    ?.let { session.execute(SetPositionHandles(time, Vector3d(it[0], it[1], it[2]), handleOut)) }
                Widgets.property("Handle out")
                Widgets.vector3("##hout", handleOut.x, handleOut.y, handleOut.z, 0.05f)
                    ?.let { session.execute(SetPositionHandles(time, handleIn, Vector3d(it[0], it[1], it[2]))) }
            }
            Widgets.endProperties()
        }
    }

    private fun keyframes(session: EditorSession, times: Set<Long>) {
        val sorted = times.sorted()
        title(
            Icon.KEYFRAME,
            "${times.size} keyframes",
            "${TimeFormat.clock(sorted.first())} to ${TimeFormat.clock(sorted.last())}"
        )
        val first = session.project.camera.keyframeAt(sorted.first())
        if (Widgets.beginProperties("multi")) {
            Widgets.property("Interpolation")
            val modes = SegmentMode.entries
            val shared = sorted.mapNotNull { session.project.camera.keyframeAt(it)?.mode }.distinct().singleOrNull()
            Widgets.segmented(
                "mode",
                modes.map { it.label },
                shared?.let { modes.indexOf(it) } ?: -1,
                0f,
                MODE_TOOLTIPS)?.let { session.execute(SetKeyframeMode(times, modes[it])) }
            Widgets.property("Nudge all")
            nudgeRow(session, times)
            Widgets.endProperties()
        }
        easingSection(
            session,
            "Easing",
            first?.easing ?: Easing.LINEAR,
            next = true,
            times = times,
            keys = emptySet()
        ) {
            session.execute(SetKeyframeEasing(times, it))
        }
        stretchSection(session, times, emptySet())
        if (Widgets.dangerButton("Delete ${times.size} keyframes")) {
            session.execute(RemoveKeyframes(times))
            session.selection = Selection.NONE
        }
    }

    private fun nudge(session: EditorSession, times: Set<Long>, delta: Long) {
        session.execute(MoveKeyframes(times, delta))
        session.selection = Selection(keyframeTimes = times.map { maxOf(0L, it + delta) }.toSet())
    }

    private fun valueKeyframe(session: EditorSession, key: ValueKey) {
        val lane = key.lane
        val frame = session.project.valueTrack(lane).at(key.nanos)
        if (frame == null) {
            session.selection = Selection.NONE
            return
        }
        title(
            HierarchyPanel.LANE_ICONS[lane.kind] ?: Icon.SLIDERS,
            "${lane.label} keyframe",
            TimeFormat.clock(key.nanos)
        )
        if (Widgets.beginProperties("value")) {
            Widgets.property("Time")
            Widgets.textInput("##time", TimeFormat.clock(key.nanos), 32, ImGuiInputTextFlags.EnterReturnsTrue)
                ?.let { text ->
                    TimeFormat.parseClock(text)?.let { target ->
                        if (target != key.nanos) {
                            session.execute(MoveValueKeyframe(lane, key.nanos, target))
                            session.selection = Selection(valueKeys = setOf(ValueKey(lane, target)))
                        }
                    }
                }
            Widgets.property(lane.label)
            Widgets.slider(
                "##value",
                frame.value.toFloat(),
                lane.min.toFloat(),
                lane.max.toFloat(),
                lane.format,
                labelOf = { lane.format(it.toDouble()) })
                ?.let { session.execute(SetValueKeyframe(lane, key.nanos, it.toDouble(), frame.mode, frame.easing)) }
            val presets = VALUE_PRESETS[lane]
            if (presets != null) {
                Widgets.property("")
                Widgets.segmented(
                    "presets",
                    presets.map { lane.format(it) },
                    presets.indexOfFirst { abs(it - frame.value) < 1e-6 },
                    0f
                )?.let { session.execute(SetValueKeyframe(lane, key.nanos, presets[it], frame.mode, frame.easing)) }
            }
            Widgets.property(
                "Ramp",
                "Linear ramps evenly, Smooth eases through neighbours, Hold keeps the value until the next keyframe"
            )
            val options = listOf(SegmentMode.LINEAR, SegmentMode.CATMULL_ROM, SegmentMode.HOLD)
            Widgets.segmented("ramp", options.map { it.label }, options.indexOf(frame.mode).coerceAtLeast(0), 0f)
                ?.let { session.execute(SetValueKeyframe(lane, key.nanos, frame.value, options[it], frame.easing)) }
            Widgets.endProperties()
        }
        easingSection(
            session,
            "Easing to next keyframe",
            frame.easing,
            next = session.project.valueTrack(lane).next(key.nanos) != null,
            times = emptySet(),
            keys = setOf(key)
        ) { session.execute(SetValueKeyframeEasing(setOf(key), it)) }
        if (Widgets.dangerButton("Delete")) {
            session.execute(RemoveValueKeyframes(setOf(key)))
            session.selection = Selection.NONE
        }
    }

    private fun viewKeyframe(session: EditorSession, time: Long) {
        val frame = session.project.views.at(time)
        if (frame == null) {
            session.selection = Selection.NONE
            return
        }
        val view = frame.value
        fun set(next: ViewState) = session.execute(SetViewKeyframe(time, next, frame.mode, frame.easing))
        title(Icon.EYE, "View switch", "${TimeFormat.clock(time)}    ${view.mode.label}")
        if (Widgets.beginProperties("view")) {
            Widgets.property("Time")
            Widgets.textInput("##time", TimeFormat.clock(time), 32, ImGuiInputTextFlags.EnterReturnsTrue)?.let { text ->
                TimeFormat.parseClock(text)?.let { target ->
                    if (target != time) {
                        session.execute(MoveViewKeyframe(time, target))
                        session.selection = Selection(viewTimes = setOf(target))
                    }
                }
            }
            Widgets.property("Mode")
            val modes = CameraMode.entries
            Widgets.segmented("mode", modes.map { it.label }, modes.indexOf(view.mode), 0f)
                ?.let { set(view.copy(mode = modes[it])) }
            Widgets.property("Target")
            Widgets.mutedText(
                if (view.targetEntityId == CameraSettings.TARGET_RECORDER) "Recorder" else entityLabel(
                    view.targetEntityId
                )
            )
            if (view.mode == CameraMode.ORBIT) {
                Widgets.property("Distance")
                Widgets.doubleSlider("##odist", view.orbitDistance, 0.5, 40.0, "%.1f")
                    ?.let { set(view.copy(orbitDistance = it)) }
                Widgets.property("Pitch")
                Widgets.doubleSlider("##opitch", view.orbitPitch, -89.0, 89.0, "%.0f°")
                    ?.let { set(view.copy(orbitPitch = it)) }
                Widgets.property("Yaw offset")
                Widgets.doubleSlider("##oyaw", view.orbitYawOffset, -180.0, 180.0, "%.0f°")
                    ?.let { set(view.copy(orbitYawOffset = it)) }
                Widgets.property("Height")
                Widgets.doubleSlider("##oheight", view.orbitHeight, -5.0, 10.0, "%.1f")
                    ?.let { set(view.copy(orbitHeight = it)) }
                Widgets.property("Spin (deg/s)")
                Widgets.doubleSlider("##ospin", view.orbitDegreesPerSecond, -90.0, 90.0, "%.1f")
                    ?.let { set(view.copy(orbitDegreesPerSecond = it)) }
            }
            if (view.mode == CameraMode.FOLLOW) {
                Widgets.property("Offset X")
                Widgets.doubleSlider("##fx", view.followOffsetX, -20.0, 20.0, "%.1f")
                    ?.let { set(view.copy(followOffsetX = it)) }
                Widgets.property("Offset Y")
                Widgets.doubleSlider("##fy", view.followOffsetY, -20.0, 20.0, "%.1f")
                    ?.let { set(view.copy(followOffsetY = it)) }
                Widgets.property("Offset Z")
                Widgets.doubleSlider("##fz", view.followOffsetZ, -20.0, 20.0, "%.1f")
                    ?.let { set(view.copy(followOffsetZ = it)) }
                Widgets.property("Look at target")
                Widgets.toggle("##flook", view.followLookAtTarget)?.let { set(view.copy(followLookAtTarget = it)) }
            }
            if (view.mode == CameraMode.CHASE) {
                Widgets.property("Distance")
                Widgets.doubleSlider("##cdist", view.chaseDistance, 0.5, 40.0, "%.1f")
                    ?.let { set(view.copy(chaseDistance = it)) }
                Widgets.property("Height")
                Widgets.doubleSlider("##cheight", view.chaseHeight, -5.0, 10.0, "%.1f")
                    ?.let { set(view.copy(chaseHeight = it)) }
                Widgets.property("Stiffness")
                Widgets.doubleSlider("##cstiff", view.chaseStiffness, 1.0, 100.0, "%.0f")
                    ?.let { set(view.copy(chaseStiffness = it)) }
                Widgets.property("Damping")
                Widgets.doubleSlider("##cdamp", view.chaseDamping, 0.5, 40.0, "%.1f")
                    ?.let { set(view.copy(chaseDamping = it)) }
            }
            if (view.mode == CameraMode.ORBIT || view.mode == CameraMode.FOLLOW || view.mode == CameraMode.CHASE) {
                Widgets.property("Track point")
                val bodyParts = TrackingBodyPart.entries
                Widgets.segmented("bodypart", bodyParts.map { it.label }, bodyParts.indexOf(view.bodyPart), 0f)
                    ?.let { set(view.copy(bodyPart = bodyParts[it])) }
                Widgets.property("Target offset")
                Widgets.doubleSlider("##tox", view.targetOffsetX, -10.0, 10.0, "X %.1f")
                    ?.let { set(view.copy(targetOffsetX = it)) }
                Widgets.doubleSlider("##toy", view.targetOffsetY, -10.0, 10.0, "Y %.1f")
                    ?.let { set(view.copy(targetOffsetY = it)) }
                Widgets.doubleSlider("##toz", view.targetOffsetZ, -10.0, 10.0, "Z %.1f")
                    ?.let { set(view.copy(targetOffsetZ = it)) }
            }
            Widgets.endProperties()
        }
        if (Widgets.accentButton("Update from camera")) set(ViewState.capture(context.host.camera.settings))
        ImGui.sameLine()
        if (Widgets.ghostButton("Apply now")) {
            view.applyTo(context.host.camera.settings)
            context.host.camera.apply()
        }
        ImGui.sameLine()
        if (Widgets.dangerButton("Delete")) {
            session.execute(RemoveViewKeyframes(setOf(time)))
            session.selection = Selection.NONE
        }
    }

    private fun clip(session: EditorSession, id: UUID) {
        val clip = session.project.clip(id)
        if (clip == null) {
            session.selection = Selection.NONE
            return
        }
        val replay = session.replay
        title(
            Icon.FILM,
            clip.title,
            "${TimeFormat.clock(clip.startNanos)} to ${TimeFormat.clock(clip.endNanos)}    ${TimeFormat.short(clip.durationNanos)}"
        )
        if (Widgets.accentButton("Play")) ClipActions.play(session, clip)
        ImGui.sameLine()
        if (Widgets.ghostButton("Bake")) ClipActions.bake(context, clip)
        Widgets.tooltip("Write a standalone .recast file with only this clip")
        ImGui.sameLine()
        if (Widgets.ghostButton("Export")) ClipActions.export(context, clip)
        ImGui.sameLine()
        if (Widgets.iconButton(
                "clip-delete",
                Icon.TRASH,
                ImGui.getFrameHeight(),
                "Delete clip",
                color = EditorTheme.RECORD.u32
            )
        ) {
            session.execute(RemoveClip(clip.id))
            context.clips.delete(clip.id)
            session.selection = Selection.NONE
            return
        }
        Widgets.header("Details")
        if (Widgets.beginProperties("clip")) {
            Widgets.property("Title")
            Widgets.textInput("##title", clip.title, 64)?.let { replace(session, clip, clip.copy(title = it)) }
            Widgets.property("In")
            Widgets.textInput("##in", TimeFormat.clock(clip.startNanos), 32, ImGuiInputTextFlags.EnterReturnsTrue)
                ?.let { text ->
                    TimeFormat.parseClock(text)
                        ?.let { if (it < clip.endNanos) replace(session, clip, clip.trimmed(it, clip.endNanos)) }
                }
            Widgets.property("Out")
            Widgets.textInput("##out", TimeFormat.clock(clip.endNanos), 32, ImGuiInputTextFlags.EnterReturnsTrue)
                ?.let { text ->
                    TimeFormat.parseClock(text)
                        ?.let { if (it > clip.startNanos) replace(session, clip, clip.trimmed(clip.startNanos, it)) }
                }
            Widgets.property("Tags")
            Widgets.textInput("##tags", clip.tags.joinToString(", "), 128)?.let {
                replace(
                    session,
                    clip,
                    clip.copy(tags = it.split(',').map { tag -> tag.trim() }.filter { tag -> tag.isNotEmpty() }.toSet())
                )
            }
            Widgets.property("Note")
            Widgets.textInput("##note", clip.note, 256)?.let { replace(session, clip, clip.copy(note = it)) }
            Widgets.property("Origin")
            Widgets.pill(
                clip.origin.name.lowercase(),
                if (clip.origin == ClipOrigin.FLASHBACK) EditorTheme.WARNING else EditorTheme.TEXT_MUTED
            )
            Widgets.endProperties()
        }
        if (Widgets.ghostButton("Set in/out to clip")) session.execute(SetInOutPoints(clip.startNanos, clip.endNanos))
        ImGui.sameLine()
        if (Widgets.ghostButton("Clip from in/out")) {
            val end = if (session.project.outPointNanos > 0L) session.project.outPointNanos else (replay?.durationNanos
                ?: clip.endNanos)
            if (end > session.project.inPointNanos) replace(
                session,
                clip,
                clip.trimmed(session.project.inPointNanos, end)
            )
        }
        Widgets.tooltip("Move this clip's range to the current in and out points")
    }

    private fun replace(session: EditorSession, clip: Clip, replacement: Clip) {
        session.execute(ReplaceClip(clip.id, replacement))
        context.clips.save(replacement)
    }

    private fun marker(session: EditorSession, id: UUID) {
        val marker = session.project.marker(id)
        if (marker == null) {
            session.selection = Selection.NONE
            return
        }
        title(Icon.MARKER, marker.label, TimeFormat.clock(marker.nanos), Widgets.rgbToU32(marker.color))
        if (Widgets.beginProperties("marker")) {
            Widgets.property("Label")
            Widgets.textInput("##label", marker.label, 64)
                ?.let { session.execute(ReplaceMarker(marker.id, marker.copy(label = it))) }
            Widgets.property("Time")
            Widgets.textInput("##time", TimeFormat.clock(marker.nanos), 32, ImGuiInputTextFlags.EnterReturnsTrue)
                ?.let { text ->
                    TimeFormat.parseClock(text)
                        ?.let { session.execute(ReplaceMarker(marker.id, marker.copy(nanos = it))) }
                }
            Widgets.property("Colour")
            for ((index, color) in EditorWorkspace.MARKER_COLORS.withIndex()) {
                if (index > 0) ImGui.sameLine()
                if (Widgets.colorSwatch("color$index", color, selected = marker.color == color)) session.execute(
                    ReplaceMarker(marker.id, marker.copy(color = color))
                )
            }
            Widgets.endProperties()
        }
        if (Widgets.ghostButton("Go to")) session.replay?.seek(marker.nanos)
        ImGui.sameLine()
        if (Widgets.dangerButton("Delete")) {
            session.execute(RemoveMarker(marker.id))
            session.selection = Selection.NONE
        }
    }

    private fun entity(session: EditorSession, target: InspectTarget.Entity) {
        val shadow = session.replay?.shadow ?: return
        val local = shadow.localPlayer
        val entity = shadow.entities[target.id]
        if (!target.isRecorder && entity == null) {
            context.selectEntity(null)
            return
        }
        val x = if (target.isRecorder) local.x else entity!!.x
        val y = if (target.isRecorder) local.y else entity!!.y
        val z = if (target.isRecorder) local.z else entity!!.z
        val ref = EntityRef(target.id, target.name, target.isPlayer, target.isRecorder, target.uuid, x, y, z)
        val kind = when {
            target.isRecorder -> "Recorder"
            target.isPlayer -> "Player"
            else -> "Entity #${target.id}"
        }
        title(if (target.isRecorder) Icon.USER else if (target.isPlayer) Icon.PERSON else Icon.CUBE, target.name, kind)
        val settings = context.host.camera.settings
        val targeted = EntityActions.isTargeted(settings, ref)
        Widgets.header("Camera")
        val modes = listOf(CameraMode.FIRST_PERSON, CameraMode.ORBIT, CameraMode.FOLLOW, CameraMode.CHASE)
        Widgets.segmented("entity-mode", modes.map { it.label }, if (targeted) modes.indexOf(settings.mode) else -1, 0f)
            ?.let { EntityActions.setMode(context, modes[it], ref) }
        ImGui.dummy(0f, EditorFonts.px(2f))
        if (Widgets.ghostButton("Fly to")) EntityActions.flyTo(context, ref)
        ImGui.sameLine()
        if (Widgets.ghostButton("Look at")) EntityActions.lookAt(context, ref)
        ImGui.sameLine()
        if (Widgets.ghostButton("Aim path here")) session.execute(SetAimTarget(EntityActions.targetId(ref)))
        Widgets.tooltip("Keep the camera path pointed at this entity")
        Widgets.header("Details")
        if (Widgets.beginProperties("entity")) {
            Widgets.property("Position")
            Widgets.mutedText(String.format("%.1f   %.1f   %.1f", x, y, z))
            if (!target.isRecorder) {
                Widgets.property("Visible")
                Widgets.toggle("##visible", !context.visuals.isHidden(target.id))?.let { visible ->
                    if (visible) context.visuals.hiddenEntities.remove(target.id) else context.visuals.hiddenEntities.add(
                        target.id
                    )
                }
            }
            if (target.uuid != null) {
                Widgets.property("UUID")
                Widgets.smallText(target.uuid.take(8), EditorTheme.TEXT_DIM.u32)
                ImGui.sameLine()
                if (Widgets.smallButton("Copy")) ImGui.setClipboardText(target.uuid)
            }
            Widgets.endProperties()
        }
        Widgets.header("View keyframe")
        Widgets.smallText("Switch the camera to this entity from the playhead on.", EditorTheme.TEXT_DIM.u32)
        for ((index, mode) in modes.withIndex()) {
            if (index > 0) ImGui.sameLine()
            if (Widgets.ghostButton("${mode.label}##vk")) EntityActions.viewKeyframe(context, mode, ref)
        }
        if (PoseTools.poseable(session, target.id)) pose(session, target.id)
    }

    private fun pose(session: EditorSession, entityId: Int) {
        val count = PoseTools.keyframeCount(session, entityId)
        Widgets.header("Pose")
        Widgets.smallText(
            if (count == 0) "Pose this model. Rotate tool (E) and click a limb in the scene, or use the sliders."
            else "$count pose keyframes. Rotate tool (E) and click a limb in the scene, or use the sliders.",
            EditorTheme.TEXT_DIM.u32
        )
        val keyed = PoseTools.hasKeyframeAtPlayhead(session, entityId)
        if (keyed) {
            if (Widgets.ghostButton("Keyed here")) Unit
            Widgets.tooltip("There is a pose keyframe at the playhead")
        } else if (Widgets.accentButton("Key pose here")) PoseTools.keyHere(session, entityId)
        ImGui.sameLine()
        if (Widgets.ghostButton("Release here")) PoseTools.releaseHere(session, entityId)
        Widgets.tooltip("Empty keyframe: limbs fade back to the game's animation by this point")
        if (count > 0) {
            ImGui.sameLine()
            if (Widgets.ghostButton("Clear all")) {
                PoseTools.clearAll(session, entityId)
                context.selectedBodyPart = null
            }
        }
        val current = PoseTools.currentPose(session, entityId)
        if (Widgets.beginProperties("pose")) {
            for (part in BodyPart.entries) {
                val posed = current[part]
                val selected = context.selectedBodyPart == part
                Widgets.property(part.label, if (posed == null) "Following the game's animation" else "Posed")
                val buttons = ImGui.getFrameHeight() * 2f + ImGui.getStyle().itemSpacingX * 3f
                val width = ((ImGui.getContentRegionAvailX() - buttons) / 3f).coerceAtLeast(EditorFonts.px(30f))
                val values = doubleArrayOf(posed?.x ?: 0.0, posed?.y ?: 0.0, posed?.z ?: 0.0)
                var changed = false
                for (axis in 0 until 3) {
                    if (axis > 0) ImGui.sameLine()
                    ImGui.pushStyleColor(
                        ImGuiCol.Text,
                        if (posed == null) EditorTheme.TEXT_MUTED.u32 else EditorTheme.TEXT.u32
                    )
                    val edited = Widgets.slider(
                        "##pose-${part.name}-$axis",
                        values[axis].toFloat(),
                        -180f,
                        180f,
                        width = width,
                        labelOf = { String.format("%s %.0f°", AXIS_NAMES[axis], it) }
                    )
                    ImGui.popStyleColor()
                    if (edited != null) {
                        values[axis] = edited.toDouble()
                        changed = true
                    }
                }
                if (changed) PoseTools.setPart(session, entityId, part, PartPose(values[0], values[1], values[2], 1.0))
                ImGui.sameLine()
                if (Widgets.iconButton(
                        "pose-pick-${part.name}",
                        Icon.TOOL_ROTATE,
                        ImGui.getFrameHeight(),
                        "Pose this limb in the scene",
                        active = selected,
                        iconScale = 0.55f
                    )
                ) {
                    context.selectedBodyPart = if (selected) null else part
                    if (!selected) context.tool = SceneTool.ROTATE
                }
                ImGui.sameLine()
                if (Widgets.iconButton(
                        "pose-release-${part.name}",
                        Icon.CLOSE,
                        ImGui.getFrameHeight(),
                        "Release this limb back to the game's animation",
                        enabled = posed != null,
                        iconScale = 0.5f
                    ) && posed != null
                ) {
                    PoseTools.releasePart(session, entityId, part)
                    if (selected) context.selectedBodyPart = null
                }
            }
            Widgets.endProperties()
        }
    }

    private fun lane(session: EditorSession, kind: LaneKind) {
        val state = session.project.lane(kind)
        val valueLane = ValueLane.entries.firstOrNull { it.kind == kind }
        val label = valueLane?.label ?: when (kind) {
            LaneKind.CAMERA -> "Camera path"
            LaneKind.POSE -> "Poses"
            else -> "View"
        }
        val count = when {
            kind == LaneKind.CAMERA -> session.project.camera.keyframeTimes().size
            kind == LaneKind.POSE -> session.project.poses.values.sumOf { it.keyframes.size }
            kind == LaneKind.VIEW -> session.project.views.keyframes.size
            valueLane != null -> session.project.valueTrack(valueLane).keyframes.size
            else -> 0
        }
        title(
            if (kind == LaneKind.CAMERA) Icon.PATH else HierarchyPanel.LANE_ICONS[kind] ?: Icon.SLIDERS,
            label,
            "$count keyframes"
        )
        if (Widgets.beginProperties("lane")) {
            Widgets.property("Enabled")
            Widgets.toggle("##enabled", !state.muted)
                ?.let { session.execute(SetLaneState(kind, state.copy(muted = !it))) }
            if (kind == LaneKind.CAMERA) {
                Widgets.property("Locked", "Prevents dragging keyframes on the timeline")
                Widgets.toggle("##locked", state.locked)
                    ?.let { session.execute(SetLaneState(kind, state.copy(locked = it))) }
            }
            if (kind == LaneKind.CAMERA || valueLane != null) {
                val pre =
                    if (valueLane == null) session.project.camera.preExtrapolation else session.project.valueTrack(
                        valueLane
                    ).preExtrapolation
                val post =
                    if (valueLane == null) session.project.camera.postExtrapolation else session.project.valueTrack(
                        valueLane
                    ).postExtrapolation
                Widgets.property("Before start", "What the track does before its first keyframe")
                Widgets.enumCombo("##pre", pre) { it.label }
                    ?.let { session.execute(SetTrackExtrapolation(valueLane, it, post)) }
                Widgets.property("After end", "Hold the last value, keep going, loop, or ping pong")
                Widgets.enumCombo("##post", post) { it.label }
                    ?.let { session.execute(SetTrackExtrapolation(valueLane, pre, it)) }
            }
            Widgets.endProperties()
        }
        if (kind == LaneKind.CAMERA || valueLane != null) {
            if (Widgets.ghostButton("Open in Graph Editor")) context.openPanel("Graph Editor")
        }
        if (kind == LaneKind.CAMERA) {
            if (Widgets.accentButton("Add keyframe here")) session.keyframeAtPlayhead(context.host.camera.currentPose())
            ImGui.sameLine()
            if (count > 0 && Widgets.ghostButton("Select all keyframes")) session.selection =
                Selection(keyframeTimes = session.project.camera.keyframeTimes().toSet())
            Widgets.smallText(
                "Path tools live in the Camera panel and the track menu on the timeline.",
                EditorTheme.TEXT_DIM.u32
            )
        } else if (valueLane != null) {
            Widgets.smallText(LANE_HINTS[valueLane] ?: "", EditorTheme.TEXT_DIM.u32)
        }
    }

    private fun project(session: EditorSession) {
        val project = session.project
        val replay = session.replay ?: return
        val recording = project.recording
        if (infoPath != recording) {
            infoPath = recording
            info = context.host.replay.describe(recording)
        }
        title(
            Icon.FOLDER,
            project.name,
            listOfNotNull(
                info?.server?.takeIf { it.isNotBlank() },
                info?.player?.takeIf { it.isNotBlank() }?.let { "as $it" }).joinToString("    ").ifEmpty { "Project" })
        if (Widgets.beginProperties("project")) {
            Widgets.property("Name")
            Widgets.textInput("##name", project.name, 64)?.let { session.execute(SetProjectName(it)) }
            Widgets.property("Recorded")
            Widgets.mutedText(info?.startEpochMillis?.takeIf { it > 0L }
                ?.let { DISPLAY_FORMAT.format(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault())) } ?: "Unknown")
            Widgets.property("Length")
            val size = runCatching { Files.size(recording) }.getOrDefault(0L)
            Widgets.mutedText("${TimeFormat.clock(replay.durationNanos)}    ${formatSize(size)}")
            Widgets.property("Range")
            val outNanos = if (project.outPointNanos > 0L) project.outPointNanos else replay.durationNanos
            Widgets.rangeText(TimeFormat.short(project.inPointNanos), TimeFormat.short(outNanos))
            ImGui.sameLine()
            if (Widgets.iconButton(
                    "range-reset",
                    Icon.CLOSE,
                    EditorFonts.px(20f),
                    "Clear the in and out points",
                    iconScale = 0.5f
                )
            ) session.execute(SetInOutPoints(0L, 0L))
            if (project.segments.size > 1 || project.isSequence) {
                Widgets.property("Sequence")
                Widgets.mutedText("${project.segments.size} segments")
                ImGui.sameLine()
                if (Widgets.smallButton("Edit")) context.openPanel("Sequence")
                if (project.sequenceStale) {
                    ImGui.sameLine()
                    Widgets.pill("rebuild needed", EditorTheme.WARNING)
                }
            }
            Widgets.property("Status")
            if (project.dirty) Widgets.pill(
                if (context.ui.autosave) "autosaving" else "unsaved",
                EditorTheme.WARNING
            ) else Widgets.pill("saved", EditorTheme.SUCCESS)
            Widgets.endProperties()
        }
        if (Widgets.accentButton("Save")) save(session)
        ImGui.sameLine()
        if (Widgets.ghostButton("Set thumbnail")) context.host.captureThumbnail(recording)
        Widgets.tooltip("Use the current view as this recording's thumbnail")
        ImGui.sameLine()
        if (Widgets.ghostButton("Library")) context.host.later { context.host.replay.close() }
        Widgets.header("Contents")
        if (Widgets.beginProperties("contents")) {
            Widgets.property("Keyframes")
            val keyframes = project.camera.keyframeTimes().size
            Widgets.mutedText(
                if (keyframes == 0) "None yet, press Ctrl+K" else "$keyframes over ${
                    TimeFormat.short(
                        project.camera.durationNanos
                    )
                }"
            )
            Widgets.property("Clips")
            Widgets.mutedText(if (project.clips.isEmpty()) "None" else "${project.clips.size}")
            Widgets.property("Markers")
            Widgets.mutedText(if (project.markers.isEmpty()) "None" else "${project.markers.size}")
            Widgets.property("Tracks")
            val tracks = ValueLane.entries.filter { project.valueTrack(it).keyframes.isNotEmpty() }
                .map { it.label } + (if (project.views.keyframes.isNotEmpty()) listOf("View") else emptyList())
            Widgets.mutedText(if (tracks.isEmpty()) "None" else tracks.joinToString(", "))
            Widgets.endProperties()
        }
    }

    private fun save(session: EditorSession) {
        val path = session.project.file ?: context.host.projectsDirectory.resolve(
            session.project.recording.fileName.toString().substringBeforeLast('.') + ".rcproj"
        )
        runCatching { ProjectCodec.save(session.project, path) }
            .onSuccess { context.status("Saved ${path.fileName}") }
            .onFailure { context.status("Save failed: ${it.message}") }
    }

    private fun entityLabel(id: Int): String {
        val shadow = context.replay?.shadow ?: return "#$id"
        val entity = shadow.entities[id] ?: return "#$id"
        return entity.uuid?.let { shadow.players.profile(it)?.name } ?: "#$id"
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1L shl 30 -> String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
        bytes >= 1L shl 20 -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
        bytes >= 1L shl 10 -> String.format("%.0f kB", bytes / 1024.0)
        else -> "$bytes B"
    }

    private fun easingSection(
        session: EditorSession,
        header: String,
        easing: Easing,
        next: Boolean,
        times: Set<Long>,
        keys: Set<ValueKey>,
        set: (Easing) -> Unit,
    ) {
        Widgets.header(header)
        val width = (ImGui.getContentRegionAvailX() - EditorFonts.px(6f) * 3f) / 4f
        if (Widgets.button("Ease", width)) session.execute(EaseKeyframes.easyEase(times, keys))
        Widgets.tooltip("Slow into and out of the selected keyframes  F9")
        ImGui.sameLine()
        if (Widgets.button("Ease in", width)) session.execute(EaseKeyframes.easeIn(times, keys))
        Widgets.tooltip("Slow down arriving at the selected keyframes  Shift+F9")
        ImGui.sameLine()
        if (Widgets.button("Ease out", width)) session.execute(EaseKeyframes.easeOut(times, keys))
        Widgets.tooltip("Start slowly leaving the selected keyframes  Ctrl+Shift+F9")
        ImGui.sameLine()
        if (Widgets.button("Linear", width)) session.execute(EaseKeyframes.linear(times, keys))
        Widgets.tooltip("Straight speed through the selected keyframes")
        if (!next) {
            Widgets.smallText("Last keyframe: nothing follows it to ease into.", EditorTheme.TEXT_DIM.u32)
            return
        }
        ImGui.dummy(0f, EditorFonts.px(4f))
        EasingWidgets.picker("easing", easing)?.let(set)
        ImGui.dummy(0f, EditorFonts.px(4f))
        val size = minOf(ImGui.getContentRegionAvailX(), EditorFonts.px(220f))
        ImGui.setCursorPosX(ImGui.getCursorPosX() + (ImGui.getContentRegionAvailX() - size) / 2f)
        EasingWidgets.editor("easing-curve", easing, size)?.let(set)
        EasingWidgets.fields("easing-fields", easing)?.let(set)
        if (Widgets.ghostButton("Open Graph Editor")) context.openPanel("Graph Editor")
    }

    private fun stretchSection(session: EditorSession, times: Set<Long>, keys: Set<ValueKey>) {
        val all = (times + keys.map { it.nanos }).sorted()
        if (all.size < 2) return
        Widgets.header("Time stretch")
        val span = all.last() - all.first()
        Widgets.smallText("Selection spans ${TimeFormat.short(span)}", EditorTheme.TEXT_DIM.u32)
        val width = (ImGui.getContentRegionAvailX() - EditorFonts.px(6f) * 3f) / 4f
        for ((index, factor) in listOf(0.5, 0.8, 1.25, 2.0).withIndex()) {
            if (index > 0) ImGui.sameLine()
            if (Widgets.button(
                    "${if (factor < 1.0) "" else "x"}${if (factor == 0.5) "1/2" else if (factor == 0.8) "4/5" else if (factor == 1.25) "1.25" else "2"}",
                    width
                )
            ) {
                val command = ScaleKeyframes(times, keys, all.first(), factor)
                session.execute(command)
                session.selection = Selection(keyframeTimes = command.resultTimes, valueKeys = command.resultValueKeys)
            }
        }
        Widgets.tooltip("Stretches the selection in time around its first keyframe. Alt-drag the ends in the Graph Editor for free scaling.")
        if (Widgets.ghostButton("Reverse order")) {
            val command = ReverseKeyframes(times, keys)
            session.execute(command)
        }
        Widgets.tooltip("Plays the selected keyframes backwards")
    }

    private companion object {
        val AXIS_NAMES = arrayOf("X", "Y", "Z")
        val MODE_TOOLTIPS = listOf(
            "Straight line at constant speed",
            "Smooth curve through neighbouring keyframes",
            "Curve with adjustable handles",
            "Hold this pose until the next keyframe"
        )
        val VALUE_PRESETS = mapOf(
            ValueLane.SPEED to listOf(0.25, 0.5, 1.0, 2.0, 4.0),
            ValueLane.FOV to listOf(30.0, 50.0, 70.0, 90.0, 110.0),
            ValueLane.TIME_OF_DAY to listOf(0.0, 6000.0, 12000.0, 18000.0),
            ValueLane.SHAKE to listOf(0.0, 0.5, 1.0, 2.0),
            ValueLane.FREEZE to listOf(0.5, 1.0, 2.0, 5.0),
        )
        val LANE_HINTS = mapOf(
            ValueLane.SPEED to "Speed keyframes ramp playback speed between them.",
            ValueLane.FOV to "FOV keyframes zoom the lens over time, independent of the camera path.",
            ValueLane.TIME_OF_DAY to "Time of day keyframes drive the sun and lighting.",
            ValueLane.SHAKE to "Shake keyframes ramp handheld camera shake in and out.",
            ValueLane.FREEZE to "Freeze keyframes hold the replay still while the camera keeps moving.",
        )
        val DISPLAY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy  HH:mm")
    }
}
