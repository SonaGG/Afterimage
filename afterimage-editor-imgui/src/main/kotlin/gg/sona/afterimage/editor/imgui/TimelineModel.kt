package gg.sona.afterimage.editor.imgui

import gg.sona.afterimage.camera.CameraKeyframe
import gg.sona.afterimage.camera.track.Keyframe
import gg.sona.afterimage.clip.Clip
import gg.sona.afterimage.editor.*
import java.util.*

sealed class TimelineItem {
    abstract val kind: LaneKind

    data class CameraKey(val nanos: Long) : TimelineItem() {
        override val kind: LaneKind get() = LaneKind.CAMERA
    }

    data class ValueKeyItem(val key: ValueKey) : TimelineItem() {
        override val kind: LaneKind get() = key.lane.kind
    }

    data class ViewKey(val nanos: Long) : TimelineItem() {
        override val kind: LaneKind get() = LaneKind.VIEW
    }

    data class PackKey(val nanos: Long) : TimelineItem() {
        override val kind: LaneKind get() = LaneKind.TEXTURE_PACK
    }

    data class MarkerItem(val id: UUID) : TimelineItem() {
        override val kind: LaneKind get() = LaneKind.MARKERS
    }

    data class TimelapseItem(val id: UUID) : TimelineItem() {
        override val kind: LaneKind get() = LaneKind.TIMELAPSE
    }

    data class ClipItem(val id: UUID) : TimelineItem() {
        override val kind: LaneKind get() = LaneKind.CLIPS
    }

    data class MomentItem(val id: UUID) : TimelineItem() {
        override val kind: LaneKind get() = LaneKind.MOMENTS
    }
}

fun Selection.has(item: TimelineItem): Boolean = when (item) {
    is TimelineItem.CameraKey -> item.nanos in keyframeTimes
    is TimelineItem.ValueKeyItem -> item.key in valueKeys
    is TimelineItem.ViewKey -> item.nanos in viewTimes
    is TimelineItem.PackKey -> item.nanos in packTimes
    is TimelineItem.MarkerItem -> item.id in markerIds
    is TimelineItem.TimelapseItem -> item.id in timelapseIds
    is TimelineItem.ClipItem -> item.id in clipIds
    is TimelineItem.MomentItem -> item.id in momentIds
}

fun Selection.with(item: TimelineItem, additive: Boolean): Selection = when (item) {
    is TimelineItem.CameraKey -> withKeyframe(item.nanos, additive)
    is TimelineItem.ValueKeyItem -> withValueKeyframe(item.key.lane, item.key.nanos, additive)
    is TimelineItem.ViewKey -> withViewKeyframe(item.nanos, additive)
    is TimelineItem.PackKey -> withPackKeyframe(item.nanos, additive)
    is TimelineItem.MarkerItem -> withMarker(item.id, additive)
    is TimelineItem.TimelapseItem -> withTimelapse(item.id, additive)
    is TimelineItem.ClipItem -> withClip(item.id, additive)
    is TimelineItem.MomentItem -> withMoment(item.id, additive)
}

fun Selection.without(item: TimelineItem): Selection = when (item) {
    is TimelineItem.CameraKey -> copy(keyframeTimes = keyframeTimes - item.nanos)
    is TimelineItem.ValueKeyItem -> copy(valueKeys = valueKeys - item.key)
    is TimelineItem.ViewKey -> copy(viewTimes = viewTimes - item.nanos)
    is TimelineItem.PackKey -> copy(packTimes = packTimes - item.nanos)
    is TimelineItem.MarkerItem -> copy(markerIds = markerIds - item.id)
    is TimelineItem.TimelapseItem -> copy(timelapseIds = timelapseIds - item.id)
    is TimelineItem.ClipItem -> copy(clipIds = clipIds - item.id)
    is TimelineItem.MomentItem -> copy(momentIds = momentIds - item.id)
}

fun Selection.plus(items: Iterable<TimelineItem>): Selection = items.fold(this) { current, item -> current.with(item, true) }

val Selection.count: Int
    get() = keyframeTimes.size + valueKeys.size + viewTimes.size + packTimes.size + markerIds.size + timelapseIds.size + clipIds.size + momentIds.size

class TimelineCache {
    private var session: EditorSession? = null
    private var version = -1L

    var keyframes: List<CameraKeyframe> = emptyList()
        private set
    var keyTimes: LongArray = LongArray(0)
        private set
    var valueKeys: Map<ValueLane, List<Keyframe<Double>>> = emptyMap()
        private set
    var viewKeys: List<Keyframe<ViewState>> = emptyList()
        private set
    var packKeys: List<Keyframe<PackState>> = emptyList()
        private set
    var clips: List<Clip> = emptyList()
        private set
    var markers: List<TimelineMarker> = emptyList()
        private set
    var timelapses: List<TimelapseMark> = emptyList()
        private set

    fun refresh(session: EditorSession): Boolean {
        val changed = session !== this.session
        if (!changed && session.commands.version == version) return false
        this.session = session
        version = session.commands.version
        val project = session.project
        keyframes = project.camera.keyframes()
        keyTimes = LongArray(keyframes.size) { keyframes[it].timeNanos }
        valueKeys = ValueLane.entries.associateWith { project.valueTrack(it).keyframes.toList() }
        viewKeys = project.views.keyframes.toList()
        packKeys = project.packs.keyframes.toList()
        clips = project.clips.sortedBy { it.startNanos }
        markers = project.markers.sortedBy { it.nanos }
        timelapses = project.timelapses.sortedBy { it.nanos }
        return changed
    }

    fun count(kind: LaneKind): Int = when (kind) {
        LaneKind.CAMERA -> keyframes.size
        LaneKind.VIEW -> viewKeys.size
        LaneKind.TEXTURE_PACK -> packKeys.size
        LaneKind.TIMELAPSE -> timelapses.size
        LaneKind.CLIPS -> clips.size
        LaneKind.MARKERS -> markers.size
        else -> ValueLane.forKind(kind)?.let { valueKeys[it]?.size } ?: 0
    }

    fun hasContent(kind: LaneKind): Boolean = count(kind) > 0

    fun values(lane: ValueLane): List<Keyframe<Double>> = valueKeys[lane] ?: emptyList()

    fun snapTimes(exclude: Selection): LongArray {
        val times = ArrayList<Long>(keyTimes.size + 16)
        for (time in keyTimes) if (time !in exclude.keyframeTimes) times += time
        for ((lane, keys) in valueKeys) for (key in keys) if (ValueKey(lane, key.timeNanos) !in exclude.valueKeys) times += key.timeNanos
        for (key in viewKeys) if (key.timeNanos !in exclude.viewTimes) times += key.timeNanos
        for (key in packKeys) if (key.timeNanos !in exclude.packTimes) times += key.timeNanos
        for (marker in markers) if (marker.id !in exclude.markerIds) times += marker.nanos
        for (mark in timelapses) if (mark.id !in exclude.timelapseIds) times += mark.nanos
        for (clip in clips) if (clip.id !in exclude.clipIds) {
            times += clip.startNanos
            times += clip.endNanos
        }
        return times.toLongArray()
    }

    fun itemsBetween(kind: LaneKind, from: Long, to: Long): List<TimelineItem> = when (kind) {
        LaneKind.CAMERA -> keyTimes.filter { it in from..to }.map { TimelineItem.CameraKey(it) }
        LaneKind.VIEW -> viewKeys.filter { it.timeNanos in from..to }.map { TimelineItem.ViewKey(it.timeNanos) }
        LaneKind.TEXTURE_PACK -> packKeys.filter { it.timeNanos in from..to }.map { TimelineItem.PackKey(it.timeNanos) }
        LaneKind.TIMELAPSE -> timelapses.filter { it.nanos in from..to }.map { TimelineItem.TimelapseItem(it.id) }
        LaneKind.MARKERS -> markers.filter { it.nanos in from..to }.map { TimelineItem.MarkerItem(it.id) }
        LaneKind.CLIPS -> clips.filter { it.endNanos >= from && it.startNanos <= to }.map { TimelineItem.ClipItem(it.id) }
        else -> ValueLane.forKind(kind)?.let { lane ->
            values(lane).filter { it.timeNanos in from..to }.map { TimelineItem.ValueKeyItem(ValueKey(lane, it.timeNanos)) }
        } ?: emptyList()
    }

    fun allItems(kind: LaneKind): List<TimelineItem> = itemsBetween(kind, Long.MIN_VALUE, Long.MAX_VALUE)
}
