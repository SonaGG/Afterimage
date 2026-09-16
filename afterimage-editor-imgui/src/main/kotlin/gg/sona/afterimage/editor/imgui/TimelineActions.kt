package gg.sona.afterimage.editor.imgui

import gg.sona.afterimage.camera.track.SegmentMode
import gg.sona.afterimage.clip.Clip
import gg.sona.afterimage.core.time.Nanos
import gg.sona.afterimage.editor.*
import gg.sona.afterimage.editor.commands.*
import java.util.*

class TimelineActions(private val context: EditorContext) {

    fun duration(session: EditorSession): Long = maxOf(1L, session.replay?.durationNanos ?: 1L)

    fun inPoint(session: EditorSession): Long = session.project.inPointNanos.coerceIn(0L, duration(session))

    fun outPoint(session: EditorSession): Long {
        val duration = duration(session)
        return if (session.project.outPointNanos > 0L) session.project.outPointNanos.coerceIn(0L, duration) else duration
    }

    fun setInPoint(session: EditorSession, nanos: Long) =
        session.execute(SetInOutPoints(nanos, maxOf(nanos, outPoint(session))))

    fun setOutPoint(session: EditorSession, nanos: Long) =
        session.execute(SetInOutPoints(minOf(inPoint(session), nanos), nanos))

    fun trimmed(session: EditorSession): Boolean = inPoint(session) > 0L || outPoint(session) < duration(session)

    fun unmute(session: EditorSession, kind: LaneKind) {
        val state = session.project.lane(kind)
        if (state.muted) session.execute(SetLaneState(kind, state.copy(muted = false)))
    }

    fun addCameraKeyframe(session: EditorSession, nanos: Long) {
        val existing = session.project.camera.keyframeAt(nanos)
        session.execute(
            SetCameraKeyframe(
                nanos,
                context.host.camera.currentPose(),
                existing?.easing ?: session.defaultEasing,
                existing?.mode ?: session.defaultKeyframeMode
            )
        )
        session.selection = Selection(keyframeTimes = setOf(nanos))
        unmute(session, LaneKind.CAMERA)
    }

    fun addValueKeyframe(session: EditorSession, lane: ValueLane, nanos: Long) {
        val value = currentValue(session, lane).coerceIn(lane.min, lane.max)
        session.execute(SetValueKeyframe(lane, nanos, value, SegmentMode.LINEAR))
        session.selection = Selection(valueKeys = setOf(ValueKey(lane, nanos)))
        unmute(session, lane.kind)
        context.timeline.shownLanes.add(lane.kind)
    }

    fun currentValue(session: EditorSession, lane: ValueLane): Double = when (lane) {
        ValueLane.SPEED -> Math.abs(session.replay?.speed ?: 1.0)
        ValueLane.FOV -> context.host.camera.currentPose().fov
        ValueLane.TIME_OF_DAY -> (context.host.worldTimeOfDay()
            ?: Math.floorMod(session.replay?.world?.timeOfDay ?: 6000L, 24000L)).toDouble()

        ValueLane.SHAKE -> context.host.camera.settings.shakeStrength
        ValueLane.FREEZE -> ValueLane.FREEZE.default
        ValueLane.SHAKE_FREQUENCY -> context.host.camera.settings.shakeFrequencyHz
        ValueLane.FOCUS -> session.focusDistanceAt(session.playheadNanos, context.host.camera.currentPose().position)
    }

    fun addViewKeyframe(session: EditorSession, nanos: Long) {
        session.execute(SetViewKeyframe(nanos, ViewState.capture(context.host.camera.settings)))
        session.selection = Selection(viewTimes = setOf(nanos))
        unmute(session, LaneKind.VIEW)
        context.timeline.shownLanes.add(LaneKind.VIEW)
    }

    fun addPackKeyframe(session: EditorSession, nanos: Long) {
        val current = session.project.packAt(nanos) ?: PackState(context.host.activeResourcePacks())
        session.execute(SetPackKeyframe(nanos, current))
        session.selection = Selection(packTimes = setOf(nanos))
        unmute(session, LaneKind.TEXTURE_PACK)
        context.timeline.shownLanes.add(LaneKind.TEXTURE_PACK)
    }

    fun addMarker(session: EditorSession, nanos: Long) {
        val marker = TimelineMarker(
            UUID.randomUUID(),
            nanos,
            "Marker ${session.project.markers.size + 1}",
            MARKER_COLORS[session.project.markers.size % MARKER_COLORS.size]
        )
        session.execute(AddMarker(marker))
        session.selection = Selection(markerIds = setOf(marker.id))
    }

    fun addTimelapse(session: EditorSession, nanos: Long) {
        val mark = TimelapseMark(UUID.randomUUID(), nanos, DEFAULT_TIMELAPSE_SKIP)
        session.execute(AddTimelapse(mark))
        session.selection = Selection(timelapseIds = setOf(mark.id))
        unmute(session, LaneKind.TIMELAPSE)
        context.timeline.shownLanes.add(LaneKind.TIMELAPSE)
    }

    fun clipFromInOut(session: EditorSession) {
        val start = inPoint(session)
        val end = outPoint(session)
        if (end - start < Nanos.PER_TICK) {
            context.status("Set in and out points first  I  O")
            return
        }
        val project = session.project
        val clip = Clip(UUID.randomUUID(), project.recording, project.sessionId, start, end, "Clip ${project.clips.size + 1}")
        session.execute(AddClip(clip))
        context.clips.save(clip)
        session.selection = Selection(clipIds = setOf(clip.id))
        context.status("Added ${clip.title}")
    }

    fun playClip(session: EditorSession, clip: Clip) {
        val replay = session.replay ?: return
        session.execute(SetInOutPoints(clip.startNanos, clip.endNanos))
        replay.seek(clip.startNanos)
        replay.play()
        session.selection = Selection(clipIds = setOf(clip.id))
    }

    fun deleteSelection(session: EditorSession) {
        val selection = session.selection
        if (selection.isEmpty) return
        for (id in selection.clipIds) context.clips.delete(id)
        session.deleteSelection()
    }

    fun clearSelection(session: EditorSession) {
        if (!session.selection.isEmpty) session.selection = Selection.NONE
    }

    fun moveSelection(session: EditorSession, deltaNanos: Long, duplicate: Boolean) {
        val selection = session.selection
        if (deltaNanos == 0L || selection.isEmpty) return
        val project = session.project
        val commands = ArrayList<EditorCommand>()
        var next = Selection.NONE
        if (selection.keyframeTimes.isNotEmpty()) {
            if (duplicate) {
                val created = HashSet<Long>()
                for (time in selection.keyframeTimes) {
                    val frame = project.camera.keyframeAt(time) ?: continue
                    val target = maxOf(0L, time + deltaNanos)
                    commands += SetCameraKeyframe(target, frame.pose, frame.easing, frame.mode)
                    created += target
                }
                next = next.copy(keyframeTimes = created)
            } else {
                commands += MoveKeyframes(selection.keyframeTimes, deltaNanos)
                next = next.copy(keyframeTimes = selection.keyframeTimes.map { maxOf(0L, it + deltaNanos) }.toSet())
            }
        }
        if (selection.valueKeys.isNotEmpty()) {
            commands += MoveValueKeyframes(selection.valueKeys, deltaNanos)
            next = next.copy(valueKeys = selection.valueKeys.map { ValueKey(it.lane, maxOf(0L, it.nanos + deltaNanos)) }.toSet())
        }
        if (selection.viewTimes.isNotEmpty()) {
            for (time in ordered(selection.viewTimes, deltaNanos)) commands += MoveViewKeyframe(time, maxOf(0L, time + deltaNanos))
            next = next.copy(viewTimes = selection.viewTimes.map { maxOf(0L, it + deltaNanos) }.toSet())
        }
        if (selection.packTimes.isNotEmpty()) {
            for (time in ordered(selection.packTimes, deltaNanos)) commands += MovePackKeyframe(time, maxOf(0L, time + deltaNanos))
            next = next.copy(packTimes = selection.packTimes.map { maxOf(0L, it + deltaNanos) }.toSet())
        }
        for (id in selection.markerIds) {
            val marker = project.marker(id) ?: continue
            commands += ReplaceMarker(id, marker.copy(nanos = maxOf(0L, marker.nanos + deltaNanos)))
        }
        for (id in selection.timelapseIds) {
            val mark = project.timelapse(id) ?: continue
            commands += ReplaceTimelapse(id, mark.copy(nanos = maxOf(0L, mark.nanos + deltaNanos)))
        }
        val duration = duration(session)
        for (id in selection.clipIds) {
            val clip = project.clip(id) ?: continue
            val start = (clip.startNanos + deltaNanos).coerceIn(0L, maxOf(0L, duration - clip.durationNanos))
            commands += ReplaceClip(id, clip.trimmed(start, start + clip.durationNanos))
        }
        next = next.copy(
            markerIds = selection.markerIds,
            timelapseIds = selection.timelapseIds,
            clipIds = selection.clipIds,
            momentIds = selection.momentIds
        )
        if (commands.isEmpty()) return
        val label = if (duplicate) "Duplicate keyframes" else if (commands.size == 1) commands[0].label else "Move ${selection.count} items"
        session.execute(if (commands.size == 1) commands[0] else CompoundCommand(label, commands))
        for (id in selection.clipIds) project.clip(id)?.let { context.clips.save(it) }
        session.selection = next
    }

    fun trimClip(session: EditorSession, clip: Clip, startNanos: Long, endNanos: Long) {
        if (clip.startNanos == startNanos && clip.endNanos == endNanos) return
        val updated = clip.trimmed(startNanos, endNanos)
        session.execute(ReplaceClip(clip.id, updated))
        context.clips.save(updated)
    }

    fun selectAll(session: EditorSession, cache: TimelineCache, lanes: List<TrackSpec>) {
        var selection = Selection.NONE
        for (lane in lanes) if (lane.selectable) selection = selection.plus(cache.allItems(lane.kind))
        session.selection = selection
    }

    private fun ordered(times: Set<Long>, delta: Long): List<Long> =
        if (delta > 0) times.sortedDescending() else times.sorted()

    companion object {
        val DEFAULT_TIMELAPSE_SKIP = 5L * Nanos.PER_SECOND
        val MARKER_COLORS = intArrayOf(0x59B36A, 0x66D4CF, 0xFFC94D, 0xE5484D, 0xC792EA, 0xF5A623, 0x8A8A8A, 0xFFFFFF)
    }
}
