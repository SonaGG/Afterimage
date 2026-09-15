package gg.sona.afterimage.editor

import gg.sona.afterimage.camera.CameraPose
import gg.sona.afterimage.camera.CameraSettings
import gg.sona.afterimage.camera.Easing
import gg.sona.afterimage.camera.Rotation
import gg.sona.afterimage.camera.track.SegmentMode
import gg.sona.afterimage.clip.Clip
import gg.sona.afterimage.core.event.Listeners
import gg.sona.afterimage.editor.commands.*
import gg.sona.afterimage.flashback.ClipRequest
import gg.sona.afterimage.replay.session.ReplaySession
import gg.sona.afterimage.world.interpolation.LinearInterpolation
import org.joml.Vector3d
import java.util.*

class EditorSession(val project: EditorProject, val replay: ReplaySession? = null) {
    val commands = CommandStack(project)
    val events = EventIndex()

    val listeners = Listeners<EditorSessionListener>()
    private val inbox = ArrayList<InboxEntry>()

    var selection: Selection = Selection.NONE
        set(value) {
            if (field == value) return
            field = value
            listeners.dispatch { it.onSelectionChanged(value) }
        }

    val playheadNanos: Long get() = replay?.positionNanos ?: 0L

    init {
        commands.listeners.add(object : CommandStackListener {
            override fun onHistoryChanged(stack: CommandStack) = listeners.dispatch { it.onProjectChanged(project) }
        })
    }

    fun execute(command: EditorCommand) = commands.execute(command)

    fun keyframeAtPlayhead(pose: CameraPose, mode: SegmentMode = defaultKeyframeMode) {
        val existing = project.camera.keyframeAt(playheadNanos)
        execute(SetCameraKeyframe(playheadNanos, pose, existing?.easing ?: defaultEasing, existing?.mode ?: mode))
        selection = Selection(keyframeTimes = setOf(playheadNanos))
        if (project.lane(LaneKind.CAMERA).muted) execute(
            SetLaneState(
                LaneKind.CAMERA,
                project.lane(LaneKind.CAMERA).copy(muted = false)
            )
        )
    }

    var defaultKeyframeMode: SegmentMode = SegmentMode.CATMULL_ROM
    var defaultEasing: Easing = Easing.LINEAR

    var autoKey: Boolean = false

    fun pathPoseAt(nanos: Long): CameraPose? {
        if (!project.cameraActiveAt(nanos)) return null
        val pose = project.camera.poseAt(nanos)
        val target = aimPosition(project.aimTargetId ?: return pose, nanos) ?: return pose
        if (target.distanceSquared(pose.position) < 1e-6) return pose
        return CameraPose(pose.position, Rotation.lookingAt(pose.position, target, pose.rotation.roll), pose.fov)
    }

    fun aimPosition(target: Int, nanos: Long): Vector3d? {
        val shadow = replay?.world ?: return null
        if (target == CameraSettings.TARGET_RECORDER) {
            val local = shadow.localPlayer
            if (!local.hasPosition) return null
            return Vector3d(local.x, local.y + AIM_HEIGHT, local.z)
        }
        val entity = shadow.entities[target] ?: return null
        val pose = entity.poseAt(nanos, LinearInterpolation)
        return Vector3d(pose.x, pose.y + AIM_HEIGHT, pose.z)
    }

    fun speedAt(nanos: Long): Double? = project.speedAt(nanos)

    fun focusDistanceAt(nanos: Long, cameraPosition: Vector3d): Double {
        val look = project.look
        val target = look.focusTargetId
        if (target != null) {
            val position = aimPosition(target, nanos)
            if (position != null) return maxOf(ValueLane.FOCUS.min, position.distance(cameraPosition))
        }
        return valueAt(ValueLane.FOCUS, nanos) ?: look.focusDistance
    }

    fun valueAt(lane: ValueLane, nanos: Long): Double? = project.valueAt(lane, nanos)

    fun deleteSelection() {
        val current = selection
        if (current.keyframeTimes.isNotEmpty()) execute(RemoveKeyframes(current.keyframeTimes))
        if (current.valueKeys.isNotEmpty()) execute(RemoveValueKeyframes(current.valueKeys))
        if (current.viewTimes.isNotEmpty()) execute(RemoveViewKeyframes(current.viewTimes))
        if (current.packTimes.isNotEmpty()) execute(RemovePackKeyframes(current.packTimes))
        current.clipIds.forEach { execute(RemoveClip(it)) }
        current.markerIds.forEach { execute(RemoveMarker(it)) }
        current.timelapseIds.forEach { execute(RemoveTimelapse(it)) }
        current.momentIds.forEach { execute(RemoveMoment(it)) }
        selection = Selection.NONE
    }

    fun inbox(): List<InboxEntry> = inbox.toList()

    private companion object {
        const val AIM_HEIGHT = 1.4
    }

    fun receive(request: ClipRequest): InboxEntry {
        val entry = InboxEntry(
            UUID.randomUUID(),
            request,
            request.toClip(project.recording, project.sessionId),
            System.currentTimeMillis()
        )
        inbox += entry
        listeners.dispatch { it.onInboxChanged(inbox()) }
        return entry
    }

    fun approve(entryId: UUID): Clip? {
        val entry = inbox.firstOrNull { it.id == entryId } ?: return null
        inbox.remove(entry)
        execute(AddClip(entry.clip))
        listeners.dispatch { it.onInboxChanged(inbox()) }
        return entry.clip
    }

    fun reject(entryId: UUID): Boolean {
        val removed = inbox.removeAll { it.id == entryId }
        if (removed) listeners.dispatch { it.onInboxChanged(inbox()) }
        return removed
    }
}
