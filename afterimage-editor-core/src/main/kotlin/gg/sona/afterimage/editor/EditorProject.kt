package gg.sona.afterimage.editor

import gg.sona.afterimage.camera.CameraPath
import gg.sona.afterimage.camera.track.Track
import gg.sona.afterimage.camera.track.interpolator.CyclicInterpolator
import gg.sona.afterimage.camera.track.interpolator.DoubleInterpolator
import gg.sona.afterimage.camera.track.interpolator.FovInterpolator
import gg.sona.afterimage.clip.Clip
import gg.sona.afterimage.editor.look.LookSettings
import gg.sona.afterimage.editor.pose.BodyPose
import gg.sona.afterimage.editor.pose.PoseInterpolator
import java.nio.file.Path
import java.util.*

class EditorProject(
    val id: UUID,
    var name: String,
    var recording: Path,
    val sessionId: UUID,
    val createdAtEpochMillis: Long = System.currentTimeMillis(),
) {
    var file: Path? = null
    val segments = ArrayList<Segment>()
    var sequenceKey: String = ""

    val isSequence: Boolean get() = segments.size > 1 || segments.any { it.inNanos > 0L || it.outNanos > 0L }

    fun currentSequenceKey(): String = segments.joinToString(";") { it.key }

    val sequenceStale: Boolean get() = isSequence && sequenceKey != currentSequenceKey()

    fun segmentSpans(): List<Pair<Long, Long>> {
        val spans = ArrayList<Pair<Long, Long>>(segments.size)
        var offset = 0L
        for ((_, inNanos, outNanos, lengthNanos) in segments) {
            val length = if (lengthNanos > 0L) lengthNanos else maxOf(0L, outNanos - inNanos)
            spans += offset to offset + length
            offset += length + SEQUENCE_GAP
        }
        return spans
    }

    val camera = CameraPath("project-camera")
    val speed = Track(DoubleInterpolator, "speed")
    val fov = Track(FovInterpolator, "fov")
    val timeOfDay = Track(CyclicInterpolator(24000.0), "timeOfDay")
    val shake = Track(DoubleInterpolator, "shake")
    val shakeFrequency = Track(DoubleInterpolator, "shakeFrequency")
    val freeze = Track(DoubleInterpolator, "freeze")
    val focus = Track(DoubleInterpolator, "focus")
    val views = Track(ViewStateInterpolator, "views")
    val packs = Track(PackStateInterpolator, "packs")
    val look = LookSettings()

    val poses = LinkedHashMap<Int, Track<BodyPose>>()
    val clips = ArrayList<Clip>()
    val markers = ArrayList<TimelineMarker>()
    val moments = ArrayList<Moment>()
    val timelapses = ArrayList<TimelapseMark>()
    val lanes = arrayListOf(
        LaneState(LaneKind.CAMERA, "Camera"),
        LaneState(LaneKind.CLIPS, "Clips"),
        LaneState(LaneKind.MARKERS, "Markers"),
        LaneState(LaneKind.EFFECTS, "Effects"),
        LaneState(LaneKind.AUDIO, "Audio"),
        LaneState(LaneKind.SPEED, "Speed"),
        LaneState(LaneKind.EVENTS, "Events"),
        LaneState(LaneKind.FOV, "FOV"),
        LaneState(LaneKind.TIME_OF_DAY, "Time of day"),
        LaneState(LaneKind.SHAKE, "Shake"),
        LaneState(LaneKind.VIEW, "View"),
        LaneState(LaneKind.FREEZE, "Freeze"),
        LaneState(LaneKind.SHAKE_FREQUENCY, "Shake frequency"),
        LaneState(LaneKind.TIMELAPSE, "Timelapse"),
        LaneState(LaneKind.PLAYERS, "Players"),
        LaneState(LaneKind.WORLD, "World"),
        LaneState(LaneKind.MOMENTS, "Moments"),
        LaneState(LaneKind.POSE, "Poses"),
        LaneState(LaneKind.FOCUS, "Focus"),
        LaneState(LaneKind.TEXTURE_PACK, "Texture pack"),
    )
    var inPointNanos: Long = 0L
    var outPointNanos: Long = 0L
    var aimTargetId: Int? = null
    var dirty: Boolean = false

    fun clip(id: UUID): Clip? = clips.firstOrNull { it.id == id }

    fun marker(id: UUID): TimelineMarker? = markers.firstOrNull { it.id == id }

    fun moment(id: UUID): Moment? = moments.firstOrNull { it.id == id }

    fun timelapse(id: UUID): TimelapseMark? = timelapses.firstOrNull { it.id == id }

    fun lane(kind: LaneKind): LaneState =
        lanes.firstOrNull { it.kind == kind } ?: LaneState(kind, kind.label).also { lanes += it }

    companion object {
        const val SEQUENCE_GAP = 1_000_000_000L
    }

    fun valueTrack(lane: ValueLane): Track<Double> = when (lane) {
        ValueLane.SPEED -> speed
        ValueLane.FOV -> fov
        ValueLane.TIME_OF_DAY -> timeOfDay
        ValueLane.SHAKE -> shake
        ValueLane.FREEZE -> freeze
        ValueLane.SHAKE_FREQUENCY -> shakeFrequency
        ValueLane.FOCUS -> focus
    }

    fun laneEnabled(kind: LaneKind): Boolean = !lane(kind).muted

    val cameraEnabled: Boolean get() = laneEnabled(LaneKind.CAMERA)

    fun cameraActiveAt(timeNanos: Long): Boolean = cameraEnabled && camera.contains(timeNanos)

    fun valueAt(lane: ValueLane, timeNanos: Long): Double? {
        if (!laneEnabled(lane.kind)) return null
        val track = valueTrack(lane)
        return if (track.covers(timeNanos)) track.valueAt(timeNanos) else null
    }

    fun speedAt(timeNanos: Long): Double? = valueAt(ValueLane.SPEED, timeNanos)

    fun viewAt(timeNanos: Long): ViewState? {
        if (!laneEnabled(LaneKind.VIEW) || views.isEmpty || timeNanos < views.firstNanos) return null
        return views.valueAt(timeNanos)
    }

    fun packAt(timeNanos: Long): PackState? {
        if (!laneEnabled(LaneKind.TEXTURE_PACK) || packs.isEmpty || timeNanos < packs.firstNanos) return null
        return packs.valueAt(timeNanos)
    }

    fun poseTrack(entityId: Int): Track<BodyPose> =
        poses.getOrPut(entityId) { Track(PoseInterpolator, "pose-$entityId") }

    fun poseAt(entityId: Int, timeNanos: Long): BodyPose? {
        if (!laneEnabled(LaneKind.POSE)) return null
        val track = poses[entityId] ?: return null
        if (track.isEmpty || timeNanos < track.firstNanos) return null
        return track.valueAt(timeNanos)?.takeIf { !it.isEmpty }
    }

    fun replaceLane(kind: LaneKind, state: LaneState) {
        val index = lanes.indexOfFirst { it.kind == kind }
        if (index >= 0) lanes[index] = state else lanes += state
    }
}
