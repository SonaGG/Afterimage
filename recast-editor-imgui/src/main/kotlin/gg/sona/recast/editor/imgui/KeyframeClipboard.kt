package gg.sona.recast.editor.imgui

import gg.sona.recast.camera.CameraKeyframe
import gg.sona.recast.camera.CameraPose
import gg.sona.recast.camera.Rotation
import gg.sona.recast.camera.track.Keyframe
import gg.sona.recast.editor.*
import gg.sona.recast.editor.commands.SetCameraKeyframe
import gg.sona.recast.editor.commands.SetValueKeyframe
import gg.sona.recast.editor.commands.SetViewKeyframe
import org.joml.Vector3d

class KeyframeClipboard(
    val camera: List<CameraKeyframe>,
    val values: List<Pair<ValueLane, Keyframe<Double>>>,
    val views: List<Keyframe<ViewState>>,
    val referencePose: CameraPose?,
) {
    val isEmpty: Boolean get() = camera.isEmpty() && values.isEmpty() && views.isEmpty()

    val count: Int get() = camera.size + values.size + views.size

    companion object {
        fun capture(session: EditorSession): KeyframeClipboard? {
            val selection = session.selection
            val camera = selection.keyframeTimes.mapNotNull { session.project.camera.keyframeAt(it) }
            val values = selection.valueKeys.mapNotNull { key ->
                session.project.valueTrack(key.lane).at(key.nanos)?.let { key.lane to it }
            }
            val views = selection.viewTimes.mapNotNull { session.project.views.at(it) }
            val origin =
                (camera.map { it.timeNanos } + values.map { it.second.timeNanos } + views.map { it.timeNanos }).minOrNull()
                    ?: return null
            val reference = camera.minByOrNull { it.timeNanos }?.pose
            return KeyframeClipboard(
                camera.map { it.copy(timeNanos = it.timeNanos - origin) },
                values.map { (lane, frame) -> lane to frame.copy(timeNanos = frame.timeNanos - origin) },
                views.map { it.copy(timeNanos = it.timeNanos - origin) },
                reference,
            )
        }
    }

    fun paste(session: EditorSession, atNanos: Long, relativeToPose: CameraPose? = null) {
        val times = HashSet<Long>()
        val keys = HashSet<ValueKey>()
        val viewTimes = HashSet<Long>()
        val reference = referencePose
        val yawDelta = if (relativeToPose != null && reference != null)
            Rotation.shortestDelta(reference.rotation.yaw, relativeToPose.rotation.yaw) else 0.0
        for ((timeNanos, pose1, mode, easing) in camera) {
            val time = atNanos + timeNanos
            val pose = if (relativeToPose != null && reference != null) {
                val offset = Vector3d(pose1.position).sub(reference.position)
                CameraPose(
                    Vector3d(relativeToPose.position).add(Rotation.rotateYaw(offset, yawDelta)),
                    pose1.rotation.copy(yaw = pose1.rotation.yaw + yawDelta),
                    pose1.fov,
                )
            } else pose1
            session.execute(SetCameraKeyframe(time, pose, easing, mode))
            times += time
        }
        for ((lane, frame) in values) {
            val time = atNanos + frame.timeNanos
            session.execute(SetValueKeyframe(lane, time, frame.value, frame.mode, frame.easing))
            keys += ValueKey(lane, time)
        }
        for ((timeNanos, value) in views) {
            val time = atNanos + timeNanos
            session.execute(SetViewKeyframe(time, value))
            viewTimes += time
        }
        session.selection = Selection(keyframeTimes = times, valueKeys = keys, viewTimes = viewTimes)
    }
}
