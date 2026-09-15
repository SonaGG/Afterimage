package gg.sona.afterimage.editor.imgui

import gg.sona.afterimage.camera.*
import gg.sona.afterimage.camera.track.SegmentMode
import gg.sona.afterimage.clip.Clip
import gg.sona.afterimage.clip.ClipOrigin
import gg.sona.afterimage.core.time.Nanos
import gg.sona.afterimage.editor.*
import gg.sona.afterimage.editor.commands.*
import gg.sona.afterimage.editor.host.RecordingInfo
import imgui.ImGui
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

    private class IconAction(
        val id: String,
        val icon: Icon,
        val tooltip: String,
        val color: Int = EditorTheme.TEXT.u32,
        val enabled: Boolean = true,
        val run: () -> Unit,
    )

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
            selection.packTimes.isNotEmpty() -> packKeyframe(session, selection.packTimes.first())
            selection.clipIds.isNotEmpty() -> clip(session, selection.clipIds.first())
            selection.markerIds.isNotEmpty() -> marker(session, selection.markerIds.first())
            else -> when (val target = context.inspect) {
                is InspectTarget.Entity -> entity(session, target)
                is InspectTarget.Lane -> lane(session, target.kind)
                else -> project(session)
            }
        }
    }

    private fun header(icon: Icon, text: String, chips: List<String>, iconColor: Int = EditorTheme.ACCENT_TEXT.u32) {
        val size = EditorFonts.px(30f)
        val x = ImGui.getCursorScreenPosX()
        val y = ImGui.getCursorScreenPosY()
        val list = ImGui.getWindowDrawList()
        list.addRectFilled(x, y, x + size, y + size, EditorTheme.CONTROL.u32, EditorFonts.px(8f))
        Icons.draw(list, icon, x + size * 0.22f, y + size * 0.22f, size * 0.56f, iconColor)
        ImGui.dummy(size, size)
        ImGui.sameLine(0f, EditorFonts.px(10f))
        ImGui.beginGroup()
        EditorFonts.with(EditorFonts.heading) {
            ImGui.textUnformatted(Widgets.clip(text, ImGui.getContentRegionAvailX()))
        }
        if (chips.isNotEmpty()) Widgets.chips(chips, lineHeight = EditorFonts.px(18f))
        ImGui.endGroup()
        ImGui.dummy(0f, EditorFonts.px(4f))
    }

    private fun section(title: String, key: String, defaultOpen: Boolean = true, trailing: String? = null): Boolean =
        Widgets.foldout(title, key, context.ui.collapsedSections, defaultOpen, trailing)

    private fun iconActions(actions: List<IconAction>, sameLine: Boolean) {
        if (actions.isEmpty()) return
        val size = ImGui.getFrameHeight()
        val gap = EditorFonts.px(4f)
        val total = actions.size * size + gap * (actions.size - 1)
        if (sameLine) {
            ImGui.sameLine(0f, EditorFonts.px(8f))
            if (ImGui.getContentRegionAvailX() < total) ImGui.newLine()
        }
        Widgets.rightAlign(total, spacing = 0f)
        for ((index, action) in actions.withIndex()) {
            if (index > 0) ImGui.sameLine(0f, gap)
            if (Widgets.iconButton(action.id, action.icon, size, action.tooltip, enabled = action.enabled, color = action.color, iconScale = 0.55f)) action.run()
        }
    }

    private fun keyframe(session: EditorSession, time: Long) {
        val frame = session.project.camera.keyframeAt(time)
        if (frame == null) {
            session.selection = Selection.NONE
            return
        }
        val replay = session.replay
        val camera = session.project.camera
        val atPlayhead = replay != null && abs(replay.positionNanos - time) < Nanos.PER_MILLI * 5
        header(
            Icon.KEYFRAME,
            "Keyframe",
            listOfNotNull(TimeFormat.clock(time), frame.mode.label, "at playhead".takeIf { atPlayhead }),
            EditorTheme.modeColor(frame.mode).u32
        )
        val previous = camera.previousKeyframeTime(time)
        val next = camera.nextKeyframeTime(time)
        if (Widgets.accentButton("Update from view")) session.execute(
            SetCameraKeyframe(time, context.host.camera.currentPose(), frame.easing, frame.mode)
        )
        Widgets.tooltip("Replace this keyframe with the current camera")
        var deleted = false
        iconActions(
            listOf(
                IconAction("kf-prev", Icon.CHEVRON_LEFT, "Previous keyframe  ,", enabled = previous != null) {
                    previous?.let { select(session, it) }
                },
                IconAction("kf-next", Icon.CHEVRON_RIGHT, "Next keyframe  .", enabled = next != null) {
                    next?.let { select(session, it) }
                },
                IconAction("kf-goto", Icon.TARGET, "Go to this keyframe", enabled = replay != null) { replay?.seek(time) },
                IconAction("kf-look", Icon.EYE, "Look through this keyframe") { lookThrough(frame.pose) },
                IconAction("kf-frame", Icon.FIT, "Frame it in the scene  F") {
                    context.host.camera.frame(frame.pose.position.x, frame.pose.position.y, frame.pose.position.z, 1.5)
                },
                IconAction("kf-delete", Icon.TRASH, "Delete keyframe", EditorTheme.RECORD.u32) {
                    session.execute(RemoveKeyframes(setOf(time)))
                    session.selection = Selection.NONE
                    deleted = true
                },
            ),
            sameLine = true
        )
        if (deleted) return

        if (section("Timing", "keyframe.timing")) {
            if (Widgets.beginProperties("timing")) {
                Widgets.property("Time")
                Widgets.textInput("##time", TimeFormat.clock(time), 32, ImGuiInputTextFlags.EnterReturnsTrue)?.let { text ->
                    TimeFormat.parseClock(text)?.let { target ->
                        if (target != time && camera.keyframeAt(target) == null) {
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
        }
        if (section("Transform", "keyframe.transform")) {
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
                        SetCameraKeyframe(time, CameraPose(pose.position, pose.rotation, it), frame.easing, frame.mode)
                    )
                }
                Widgets.endProperties()
            }
        }
        if (frame.mode == SegmentMode.BEZIER) bezierHandles(session, time)
        easingSection(
            session,
            "keyframe.easing",
            frame.easing,
            next = next != null,
            times = setOf(time),
            keys = emptySet()
        ) { session.execute(SetKeyframeEasing(setOf(time), it)) }
    }

    private fun select(session: EditorSession, time: Long) {
        session.selection = Selection(keyframeTimes = setOf(time))
        session.replay?.seek(time)
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
        val handleIn = keyframe.handleIn
        val handleOut = keyframe.handleOut
        if (!section("Bezier handles", "keyframe.bezier", trailing = if (handleIn != null) "Custom" else "Automatic")) return
        if (Widgets.beginProperties("bezier")) {
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
        header(
            Icon.KEYFRAME,
            "${times.size} keyframes",
            listOf(
                "${TimeFormat.short(sorted.first())} to ${TimeFormat.short(sorted.last())}",
                TimeFormat.short(sorted.last() - sorted.first())
            )
        )
        val first = session.project.camera.keyframeAt(sorted.first())
        if (Widgets.ghostButton("Select all keyframes")) session.selection =
            Selection(keyframeTimes = session.project.camera.keyframeTimes().toSet())
        var deleted = false
        iconActions(
            listOf(
                IconAction("kfs-goto", Icon.TARGET, "Go to the first selected keyframe", enabled = session.replay != null) {
                    session.replay?.seek(sorted.first())
                },
                IconAction("kfs-delete", Icon.TRASH, "Delete ${times.size} keyframes", EditorTheme.RECORD.u32) {
                    session.execute(RemoveKeyframes(times))
                    session.selection = Selection.NONE
                    deleted = true
                },
            ),
            sameLine = true
        )
        if (deleted) return
        if (section("Keyframes", "keyframes.timing")) {
            if (Widgets.beginProperties("multi")) {
                Widgets.property("Interpolation")
                val modes = SegmentMode.entries
                val shared = sorted.mapNotNull { session.project.camera.keyframeAt(it)?.mode }.distinct().singleOrNull()
                Widgets.segmented("mode", modes.map { it.label }, shared?.let { modes.indexOf(it) } ?: -1, 0f, MODE_TOOLTIPS)
                    ?.let { session.execute(SetKeyframeMode(times, modes[it])) }
                Widgets.property("Nudge all")
                nudgeRow(session, times)
                Widgets.endProperties()
            }
        }
        easingSection(
            session,
            "keyframes.easing",
            first?.easing ?: Easing.LINEAR,
            next = true,
            times = times,
            keys = emptySet()
        ) { session.execute(SetKeyframeEasing(times, it)) }
        stretchSection(session, times, emptySet())
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
        header(
            laneIcon(lane.kind),
            "${lane.label} keyframe",
            listOf(TimeFormat.clock(key.nanos), lane.format(frame.value))
        )
        if (Widgets.ghostButton("Open in Graph Editor")) context.openPanel("Graph Editor")
        var deleted = false
        iconActions(
            listOf(
                IconAction("vk-goto", Icon.TARGET, "Go to this keyframe", enabled = session.replay != null) {
                    session.replay?.seek(key.nanos)
                },
                IconAction("vk-delete", Icon.TRASH, "Delete keyframe", EditorTheme.RECORD.u32) {
                    session.execute(RemoveValueKeyframes(setOf(key)))
                    session.selection = Selection.NONE
                    deleted = true
                },
            ),
            sameLine = true
        )
        if (deleted) return
        if (section("Value", "value.value")) {
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
                    Widgets.property("Presets")
                    for ((index, preset) in presets.withIndex()) {
                        if (index > 0) ImGui.sameLine(0f, EditorFonts.px(4f))
                        if (Widgets.chipButton("preset-$index", lane.format(preset), abs(preset - frame.value) < 1e-6)) {
                            session.execute(SetValueKeyframe(lane, key.nanos, preset, frame.mode, frame.easing))
                        }
                    }
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
        }
        easingSection(
            session,
            "value.easing",
            frame.easing,
            next = session.project.valueTrack(lane).next(key.nanos) != null,
            times = emptySet(),
            keys = setOf(key)
        ) { session.execute(SetValueKeyframeEasing(setOf(key), it)) }
    }

    private fun packKeyframe(session: EditorSession, time: Long) {
        val frame = session.project.packs.at(time)
        if (frame == null) {
            session.selection = Selection.NONE
            return
        }
        val state = frame.value
        header(
            Icon.PACKAGE,
            "Texture pack switch",
            listOf(TimeFormat.clock(time), if (state.isDefault) "Default textures" else "${state.packs.size} packs")
        )
        var deleted = false
        iconActions(
            listOf(
                IconAction("pk-goto", Icon.TARGET, "Go to this keyframe", enabled = session.replay != null) {
                    session.replay?.seek(time)
                },
                IconAction("pk-delete", Icon.TRASH, "Delete keyframe", EditorTheme.RECORD.u32) {
                    session.execute(RemovePackKeyframes(setOf(time)))
                    session.selection = Selection.NONE
                    deleted = true
                },
            ),
            sameLine = false
        )
        if (deleted) return
        if (section("Packs", "pack.packs")) {
            if (Widgets.beginProperties("pack")) {
                Widgets.property("Time")
                Widgets.textInput("##time", TimeFormat.clock(time), 32, ImGuiInputTextFlags.EnterReturnsTrue)?.let { text ->
                    TimeFormat.parseClock(text)?.let { target ->
                        if (target != time) {
                            session.execute(MovePackKeyframe(time, target))
                            session.selection = Selection(packTimes = setOf(target))
                        }
                    }
                }
                Widgets.property("Default textures", "No resource packs from here on")
                Widgets.toggle("##default", state.isDefault)?.let { if (it) session.execute(SetPackKeyframe(time, PackState.DEFAULT)) }
                val available = context.host.resourcePacks()
                for (pack in available) {
                    Widgets.property(pack.removeSuffix(".zip"))
                    Widgets.toggle("##pack-$pack", pack in state.packs)?.let { session.execute(SetPackKeyframe(time, state.toggled(pack))) }
                }
                if (available.isEmpty()) {
                    Widgets.property("Packs")
                    Widgets.mutedText("None in the resourcepacks folder")
                }
                Widgets.endProperties()
            }
            Widgets.wrappedText(
                "Packs lower in the list override the ones above, like the resource pack screen. Switching reloads textures, which takes a moment.",
                EditorTheme.TEXT_DIM.u32
            )
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
        val target = if (view.targetEntityId == CameraSettings.TARGET_RECORDER) "Recorder" else entityLabel(view.targetEntityId)
        header(Icon.EYE, "View switch", listOf(TimeFormat.clock(time), view.mode.label, target))
        if (Widgets.accentButton("Update from camera")) set(ViewState.capture(context.host.camera.settings))
        Widgets.tooltip("Store the current camera mode and target in this keyframe")
        ImGui.sameLine()
        if (Widgets.ghostButton("Apply now")) {
            view.applyTo(context.host.camera.settings)
            context.host.camera.apply()
        }
        Widgets.tooltip("Switch the camera to this view right away")
        var deleted = false
        iconActions(
            listOf(
                IconAction("vw-goto", Icon.TARGET, "Go to this keyframe", enabled = session.replay != null) {
                    session.replay?.seek(time)
                },
                IconAction("vw-delete", Icon.TRASH, "Delete keyframe", EditorTheme.RECORD.u32) {
                    session.execute(RemoveViewKeyframes(setOf(time)))
                    session.selection = Selection.NONE
                    deleted = true
                },
            ),
            sameLine = true
        )
        if (deleted) return
        if (section("View", "view.view")) {
            if (Widgets.beginProperties("view")) {
                Widgets.property("Time")
                Widgets.textInput("##time", TimeFormat.clock(time), 32, ImGuiInputTextFlags.EnterReturnsTrue)?.let { text ->
                    TimeFormat.parseClock(text)?.let { moved ->
                        if (moved != time) {
                            session.execute(MoveViewKeyframe(time, moved))
                            session.selection = Selection(viewTimes = setOf(moved))
                        }
                    }
                }
                Widgets.property("Mode")
                val modes = CameraMode.entries
                Widgets.segmented("mode", modes.map { it.label }, modes.indexOf(view.mode), 0f)
                    ?.let { set(view.copy(mode = modes[it])) }
                Widgets.property("Target")
                Widgets.mutedText(target)
                Widgets.endProperties()
            }
        }
        val tracks = view.mode == CameraMode.ORBIT || view.mode == CameraMode.FOLLOW || view.mode == CameraMode.CHASE
        if (view.mode == CameraMode.ORBIT && section("Orbit", "view.orbit")) {
            if (Widgets.beginProperties("orbit")) {
                Widgets.property("Distance")
                Widgets.doubleSlider("##odist", view.orbitDistance, 0.5, 40.0, "%.1f")?.let { set(view.copy(orbitDistance = it)) }
                Widgets.property("Pitch")
                Widgets.doubleSlider("##opitch", view.orbitPitch, -89.0, 89.0, "%.0f°")?.let { set(view.copy(orbitPitch = it)) }
                Widgets.property("Yaw offset")
                Widgets.doubleSlider("##oyaw", view.orbitYawOffset, -180.0, 180.0, "%.0f°")?.let { set(view.copy(orbitYawOffset = it)) }
                Widgets.property("Height")
                Widgets.doubleSlider("##oheight", view.orbitHeight, -5.0, 10.0, "%.1f")?.let { set(view.copy(orbitHeight = it)) }
                Widgets.property("Spin", "Degrees per second")
                Widgets.doubleSlider("##ospin", view.orbitDegreesPerSecond, -90.0, 90.0, "%.1f°/s")?.let { set(view.copy(orbitDegreesPerSecond = it)) }
                Widgets.endProperties()
            }
        }
        if (view.mode == CameraMode.FOLLOW && section("Follow", "view.follow")) {
            if (Widgets.beginProperties("follow")) {
                Widgets.property("Offset X")
                Widgets.doubleSlider("##fx", view.followOffsetX, -20.0, 20.0, "%.1f")?.let { set(view.copy(followOffsetX = it)) }
                Widgets.property("Offset Y")
                Widgets.doubleSlider("##fy", view.followOffsetY, -20.0, 20.0, "%.1f")?.let { set(view.copy(followOffsetY = it)) }
                Widgets.property("Offset Z")
                Widgets.doubleSlider("##fz", view.followOffsetZ, -20.0, 20.0, "%.1f")?.let { set(view.copy(followOffsetZ = it)) }
                Widgets.property("Look at target")
                Widgets.toggle("##flook", view.followLookAtTarget)?.let { set(view.copy(followLookAtTarget = it)) }
                Widgets.endProperties()
            }
        }
        if (view.mode == CameraMode.CHASE && section("Chase", "view.chase")) {
            if (Widgets.beginProperties("chase")) {
                Widgets.property("Distance")
                Widgets.doubleSlider("##cdist", view.chaseDistance, 0.5, 40.0, "%.1f")?.let { set(view.copy(chaseDistance = it)) }
                Widgets.property("Height")
                Widgets.doubleSlider("##cheight", view.chaseHeight, -5.0, 10.0, "%.1f")?.let { set(view.copy(chaseHeight = it)) }
                Widgets.property("Stiffness")
                Widgets.doubleSlider("##cstiff", view.chaseStiffness, 1.0, 100.0, "%.0f")?.let { set(view.copy(chaseStiffness = it)) }
                Widgets.property("Damping")
                Widgets.doubleSlider("##cdamp", view.chaseDamping, 0.5, 40.0, "%.1f")?.let { set(view.copy(chaseDamping = it)) }
                Widgets.endProperties()
            }
        }
        if (tracks && section("Tracking", "view.tracking")) {
            if (Widgets.beginProperties("tracking")) {
                Widgets.property("Track point")
                val bodyParts = TrackingBodyPart.entries
                Widgets.segmented("bodypart", bodyParts.map { it.label }, bodyParts.indexOf(view.bodyPart), 0f)
                    ?.let { set(view.copy(bodyPart = bodyParts[it])) }
                Widgets.property("Offset X")
                Widgets.doubleSlider("##tox", view.targetOffsetX, -10.0, 10.0, "%.1f")?.let { set(view.copy(targetOffsetX = it)) }
                Widgets.property("Offset Y")
                Widgets.doubleSlider("##toy", view.targetOffsetY, -10.0, 10.0, "%.1f")?.let { set(view.copy(targetOffsetY = it)) }
                Widgets.property("Offset Z")
                Widgets.doubleSlider("##toz", view.targetOffsetZ, -10.0, 10.0, "%.1f")?.let { set(view.copy(targetOffsetZ = it)) }
                Widgets.endProperties()
            }
        }
    }

    private fun clip(session: EditorSession, id: UUID) {
        val clip = session.project.clip(id)
        if (clip == null) {
            session.selection = Selection.NONE
            return
        }
        val replay = session.replay
        header(
            Icon.FILM,
            clip.title,
            listOf(
                "${TimeFormat.short(clip.startNanos)} to ${TimeFormat.short(clip.endNanos)}",
                TimeFormat.short(clip.durationNanos),
                clip.origin.name.lowercase()
            )
        )
        if (Widgets.accentButton("Play")) ClipActions.play(session, clip)
        ImGui.sameLine()
        if (Widgets.ghostButton("Bake")) ClipActions.bake(context, clip)
        Widgets.tooltip("Write a standalone .afterimage file with only this clip")
        ImGui.sameLine()
        if (Widgets.ghostButton("Export")) ClipActions.export(context, clip)
        var deleted = false
        iconActions(
            listOf(
                IconAction("clip-goto", Icon.TARGET, "Go to the clip start", enabled = replay != null) { replay?.seek(clip.startNanos) },
                IconAction("clip-delete", Icon.TRASH, "Delete clip", EditorTheme.RECORD.u32) {
                    session.execute(RemoveClip(clip.id))
                    context.clips.delete(clip.id)
                    session.selection = Selection.NONE
                    deleted = true
                },
            ),
            sameLine = true
        )
        if (deleted) return
        if (section("Details", "clip.details")) {
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
        }
        if (section("Range", "clip.range")) {
            if (Widgets.ghostButton("Set in/out to clip")) session.execute(SetInOutPoints(clip.startNanos, clip.endNanos))
            Widgets.tooltip("Move the in and out points to this clip")
            ImGui.sameLine()
            if (Widgets.ghostButton("Clip from in/out")) {
                val end = if (session.project.outPointNanos > 0L) session.project.outPointNanos else (replay?.durationNanos ?: clip.endNanos)
                if (end > session.project.inPointNanos) replace(session, clip, clip.trimmed(session.project.inPointNanos, end))
            }
            Widgets.tooltip("Move this clip's range to the current in and out points")
        }
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
        header(Icon.MARKER, marker.label, listOf(TimeFormat.clock(marker.nanos), marker.kind.name.lowercase()), Widgets.rgbToU32(marker.color))
        if (Widgets.ghostButton("Go to")) session.replay?.seek(marker.nanos)
        var deleted = false
        iconActions(
            listOf(
                IconAction("marker-delete", Icon.TRASH, "Delete marker", EditorTheme.RECORD.u32) {
                    session.execute(RemoveMarker(marker.id))
                    session.selection = Selection.NONE
                    deleted = true
                },
            ),
            sameLine = true
        )
        if (deleted) return
        if (section("Marker", "marker.details")) {
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
        val hidden = !target.isRecorder && context.visuals.isHidden(target.id)
        header(
            if (target.isRecorder) Icon.USER else if (target.isPlayer) Icon.PERSON else Icon.CUBE,
            target.name,
            listOfNotNull(kind, "hidden".takeIf { hidden })
        )
        val settings = context.host.camera.settings
        val targeted = EntityActions.isTargeted(settings, ref)
        if (Widgets.ghostButton("Fly to")) EntityActions.flyTo(context, ref)
        Widgets.tooltip("Fly the scene camera next to this entity")
        ImGui.sameLine()
        if (Widgets.ghostButton("Look at")) EntityActions.lookAt(context, ref)
        Widgets.tooltip("Turn the scene camera toward this entity")
        iconActions(
            listOfNotNull(
                IconAction("entity-aim", Icon.PATH, "Keep the camera path pointed at this entity", active(session.project.aimTargetId == EntityActions.targetId(ref))) {
                    session.execute(SetAimTarget(EntityActions.targetId(ref)))
                },
                if (target.isRecorder) null else IconAction(
                    "entity-visible",
                    if (hidden) Icon.EYE_OFF else Icon.EYE,
                    if (hidden) "Show this entity" else "Hide this entity"
                ) {
                    if (hidden) context.visuals.hiddenEntities.remove(target.id) else context.visuals.hiddenEntities.add(target.id)
                },
            ),
            sameLine = true
        )
        if (section("Camera", "entity.camera", trailing = if (targeted) settings.mode.label else null)) {
            val modes = listOf(CameraMode.FIRST_PERSON, CameraMode.ORBIT, CameraMode.FOLLOW, CameraMode.CHASE)
            Widgets.segmented("entity-mode", modes.map { it.label }, if (targeted) modes.indexOf(settings.mode) else -1, 0f)
                ?.let { EntityActions.setMode(context, modes[it], ref) }
            Widgets.smallText("Look at this entity through a camera mode, from now on.", EditorTheme.TEXT_DIM.u32)
        }
        if (section("View keyframe", "entity.view", defaultOpen = false)) {
            Widgets.smallText("Switch the camera to this entity from the playhead on.", EditorTheme.TEXT_DIM.u32)
            val modes = listOf(CameraMode.FIRST_PERSON, CameraMode.ORBIT, CameraMode.FOLLOW, CameraMode.CHASE)
            for ((index, mode) in modes.withIndex()) {
                if (index > 0) ImGui.sameLine()
                if (Widgets.ghostButton("${mode.label}##vk")) EntityActions.viewKeyframe(context, mode, ref)
            }
        }
        if (section("Details", "entity.details")) {
            if (Widgets.beginProperties("entity")) {
                Widgets.property("Position")
                Widgets.chips(listOf(String.format("%.1f", x), String.format("%.1f", y), String.format("%.1f", z)))
                Widgets.property("Entity id")
                Widgets.mutedText("#${target.id}")
                if (target.uuid != null) {
                    Widgets.property("UUID")
                    Widgets.smallText(target.uuid.take(8), EditorTheme.TEXT_DIM.u32)
                    ImGui.sameLine()
                    if (Widgets.smallButton("Copy")) ImGui.setClipboardText(target.uuid)
                }
                Widgets.endProperties()
            }
        }
    }

    private fun active(value: Boolean): Int = if (value) EditorTheme.SELECTION.u32 else EditorTheme.TEXT.u32

    private fun lane(session: EditorSession, kind: LaneKind) {
        val state = session.project.lane(kind)
        val valueLane = ValueLane.entries.firstOrNull { it.kind == kind }
        val label = valueLane?.label ?: when (kind) {
            LaneKind.CAMERA -> "Camera path"
            LaneKind.TEXTURE_PACK -> "Texture packs"
            else -> "View"
        }
        val count = when {
            kind == LaneKind.CAMERA -> session.project.camera.keyframeTimes().size
            kind == LaneKind.VIEW -> session.project.views.keyframes.size
            kind == LaneKind.TEXTURE_PACK -> session.project.packs.keyframes.size
            valueLane != null -> session.project.valueTrack(valueLane).keyframes.size
            else -> 0
        }
        header(
            laneIcon(kind),
            label,
            listOfNotNull("$count keyframes", "disabled".takeIf { state.muted })
        )
        if (kind == LaneKind.CAMERA) {
            if (Widgets.accentButton("Add keyframe here")) session.keyframeAtPlayhead(context.host.camera.currentPose())
            Widgets.tooltip("Add a camera keyframe at the playhead from the current view  Ctrl+K")
            ImGui.sameLine()
        }
        if (kind == LaneKind.CAMERA || valueLane != null) {
            if (Widgets.ghostButton("Graph Editor")) context.openPanel("Graph Editor")
        }
        if (kind == LaneKind.CAMERA && count > 0) {
            ImGui.sameLine()
            if (Widgets.ghostButton("Select all")) session.selection = Selection(keyframeTimes = session.project.camera.keyframeTimes().toSet())
        }
        if (section("Track", "lane.track")) {
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
                    val pre = if (valueLane == null) session.project.camera.preExtrapolation else session.project.valueTrack(valueLane).preExtrapolation
                    val post = if (valueLane == null) session.project.camera.postExtrapolation else session.project.valueTrack(valueLane).postExtrapolation
                    Widgets.property("Before start", "What the track does before its first keyframe")
                    Widgets.enumCombo("##pre", pre) { it.label }
                        ?.let { session.execute(SetTrackExtrapolation(valueLane, it, post)) }
                    Widgets.property("After end", "Hold the last value, keep going, loop, or ping pong")
                    Widgets.enumCombo("##post", post) { it.label }
                        ?.let { session.execute(SetTrackExtrapolation(valueLane, pre, it)) }
                }
                Widgets.endProperties()
            }
            val hint = when {
                kind == LaneKind.CAMERA -> "Path tools live in the Camera panel and the track menu on the timeline."
                valueLane != null -> LANE_HINTS[valueLane]
                else -> null
            }
            if (hint != null) Widgets.wrappedText(hint, EditorTheme.TEXT_DIM.u32)
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
        header(
            Icon.FOLDER,
            project.name,
            listOfNotNull(
                info?.server?.takeIf { it.isNotBlank() },
                info?.player?.takeIf { it.isNotBlank() }?.let { "as $it" },
                if (project.dirty) (if (context.ui.autosave) "autosaving" else "unsaved") else "saved"
            )
        )
        if (Widgets.accentButton("Save")) save(session)
        ImGui.sameLine()
        if (Widgets.ghostButton("Set thumbnail")) context.host.captureThumbnail(recording)
        Widgets.tooltip("Use the current view as this recording's thumbnail")
        iconActions(
            listOf(
                IconAction("project-export", Icon.EXPORT, "Export video  Ctrl+E") { context.openPanel("Export") },
                IconAction("project-library", Icon.LIST, "Close the replay and go back to the library") {
                    context.host.later { context.host.replay.close() }
                },
            ),
            sameLine = true
        )
        if (section("Project", "project.details")) {
            if (Widgets.beginProperties("project")) {
                Widgets.property("Name")
                Widgets.textInput("##name", project.name, 64)?.let { session.execute(SetProjectName(it)) }
                Widgets.property("Recorded")
                Widgets.mutedText(info?.startEpochMillis?.takeIf { it > 0L }
                    ?.let { DISPLAY_FORMAT.format(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault())) } ?: "Unknown")
                Widgets.property("Length")
                val size = runCatching { Files.size(recording) }.getOrDefault(0L)
                Widgets.chips(listOf(TimeFormat.clock(replay.durationNanos), formatSize(size)))
                Widgets.property("Range")
                val outNanos = if (project.outPointNanos > 0L) project.outPointNanos else replay.durationNanos
                Widgets.rangeText(TimeFormat.short(project.inPointNanos), TimeFormat.short(outNanos))
                if (project.inPointNanos > 0L || project.outPointNanos > 0L) {
                    ImGui.sameLine()
                    if (Widgets.iconButton("range-reset", Icon.CLOSE, EditorFonts.px(20f), "Clear the in and out points", iconScale = 0.5f)) {
                        session.execute(SetInOutPoints(0L, 0L))
                    }
                }
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
                Widgets.endProperties()
            }
        }
        if (section("Contents", "project.contents")) {
            if (Widgets.beginProperties("contents")) {
                Widgets.property("Keyframes")
                val keyframes = project.camera.keyframeTimes().size
                Widgets.mutedText(
                    if (keyframes == 0) "None yet, press Ctrl+K" else "$keyframes over ${TimeFormat.short(project.camera.durationNanos)}"
                )
                Widgets.property("Clips")
                Widgets.mutedText(if (project.clips.isEmpty()) "None" else "${project.clips.size}")
                Widgets.property("Markers")
                Widgets.mutedText(if (project.markers.isEmpty()) "None" else "${project.markers.size}")
                Widgets.property("Tracks")
                val tracks = ValueLane.entries.filter { project.valueTrack(it).keyframes.isNotEmpty() }
                    .map { it.label } + (if (project.views.keyframes.isNotEmpty()) listOf("View") else emptyList())
                if (tracks.isEmpty()) Widgets.mutedText("None") else Widgets.chips(tracks)
                Widgets.endProperties()
            }
        }
    }

    private fun save(session: EditorSession) {
        val path = session.project.file ?: ProjectStore.forRecording(context.host.projectsDirectory, session.project.recording)
        runCatching { ProjectCodec.save(session.project, path) }
            .onSuccess { context.status("Saved ${path.fileName}") }
            .onFailure { context.status("Save failed: ${it.message}") }
    }

    private fun entityLabel(id: Int): String {
        val shadow = context.replay?.shadow ?: return "#$id"
        val entity = shadow.entities[id] ?: return "#$id"
        return entity.uuid?.let { shadow.players.profile(it)?.name } ?: "#$id"
    }

    private fun laneIcon(kind: LaneKind): Icon = when (kind) {
        LaneKind.CAMERA -> Icon.PATH
        LaneKind.SPEED -> Icon.GAUGE
        LaneKind.FOV -> Icon.APERTURE
        LaneKind.TIME_OF_DAY -> Icon.SUN
        LaneKind.SHAKE, LaneKind.SHAKE_FREQUENCY -> Icon.WAVE
        LaneKind.VIEW -> Icon.EYE
        LaneKind.FREEZE -> Icon.SNOWFLAKE
        LaneKind.FOCUS -> Icon.FOCUS
        LaneKind.TEXTURE_PACK -> Icon.PACKAGE
        else -> Icon.SLIDERS
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1L shl 30 -> String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
        bytes >= 1L shl 20 -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
        bytes >= 1L shl 10 -> String.format("%.0f kB", bytes / 1024.0)
        else -> "$bytes B"
    }

    private fun easingSection(
        session: EditorSession,
        key: String,
        easing: Easing,
        next: Boolean,
        times: Set<Long>,
        keys: Set<ValueKey>,
        set: (Easing) -> Unit,
    ) {
        if (!section("Easing", key, trailing = if (next) easing.label else "Last keyframe")) return
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
            Widgets.wrappedText("Nothing follows the last keyframe, so there is no segment to ease.", EditorTheme.TEXT_DIM.u32)
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
        val span = all.last() - all.first()
        if (!section("Time stretch", "keyframes.stretch", trailing = TimeFormat.short(span))) return
        val width = (ImGui.getContentRegionAvailX() - EditorFonts.px(6f) * 3f) / 4f
        for ((index, factor) in STRETCH_FACTORS.withIndex()) {
            if (index > 0) ImGui.sameLine()
            if (Widgets.button(STRETCH_LABELS[index], width)) {
                val command = ScaleKeyframes(times, keys, all.first(), factor)
                session.execute(command)
                session.selection = Selection(keyframeTimes = command.resultTimes, valueKeys = command.resultValueKeys)
            }
            Widgets.tooltip("Stretch the selection in time around its first keyframe. Alt-drag the ends in the Graph Editor for free scaling.")
        }
        if (Widgets.ghostButton("Reverse order")) session.execute(ReverseKeyframes(times, keys))
        Widgets.tooltip("Plays the selected keyframes backwards")
    }

    private companion object {
        val MODE_TOOLTIPS = listOf(
            "Straight line at constant speed",
            "Smooth curve through neighbouring keyframes",
            "Curve with adjustable handles",
            "Hold this pose until the next keyframe"
        )
        val STRETCH_FACTORS = listOf(0.5, 0.8, 1.25, 2.0)
        val STRETCH_LABELS = listOf("1/2", "4/5", "x1.25", "x2")
        val VALUE_PRESETS = mapOf(
            ValueLane.SPEED to listOf(0.25, 0.5, 1.0, 2.0, 4.0),
            ValueLane.FOV to listOf(30.0, 50.0, 70.0, 90.0, 110.0),
            ValueLane.TIME_OF_DAY to listOf(0.0, 6000.0, 12000.0, 18000.0),
            ValueLane.SHAKE to listOf(0.0, 0.5, 1.0, 2.0),
            ValueLane.FREEZE to listOf(0.5, 1.0, 2.0, 5.0),
            ValueLane.FOCUS to listOf(2.0, 4.0, 8.0, 16.0, 32.0),
        )
        val LANE_HINTS = mapOf(
            ValueLane.SPEED to "Speed keyframes ramp playback speed between them.",
            ValueLane.FOV to "FOV keyframes zoom the lens over time, independent of the camera path.",
            ValueLane.TIME_OF_DAY to "Time of day keyframes drive the sun and lighting.",
            ValueLane.SHAKE to "Shake keyframes ramp handheld camera shake in and out.",
            ValueLane.FREEZE to "Freeze keyframes hold the replay still while the camera keeps moving.",
            ValueLane.FOCUS to "Focus keyframes rack the depth of field focus distance over time.",
        )
        val DISPLAY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy  HH:mm")
    }
}
