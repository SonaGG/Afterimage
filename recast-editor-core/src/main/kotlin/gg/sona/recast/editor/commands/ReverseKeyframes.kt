package gg.sona.recast.editor.commands

import gg.sona.recast.camera.CameraPathKey
import gg.sona.recast.camera.track.Keyframe
import gg.sona.recast.editor.EditorCommand
import gg.sona.recast.editor.EditorProject
import gg.sona.recast.editor.ValueKey
import gg.sona.recast.editor.ValueLane

class ReverseKeyframes(private val times: Set<Long>, private val valueKeys: Set<ValueKey>) : EditorCommand {

    private var cameraBefore: List<CameraPathKey> = emptyList()
    private var valuesBefore: List<Pair<ValueLane, Keyframe<Double>>> = emptyList()
    private var cameraAfter: List<Long> = emptyList()
    private var valuesAfter: List<ValueKey> = emptyList()

    override val label: String get() = "Reverse keyframes"

    override fun apply(project: EditorProject) {
        val camera = project.camera
        cameraBefore = times.mapNotNull { camera.snapshot(it) }.sortedBy { it.timeNanos }
        valuesBefore =
            valueKeys.mapNotNull { key -> project.valueTrack(key.lane).at(key.nanos)?.let { key.lane to it } }
        val all = (cameraBefore.map { it.timeNanos } + valuesBefore.map { it.second.timeNanos })
        if (all.size < 2) return
        val first = all.min()
        val last = all.max()
        cameraBefore.forEach { camera.removeKeyframe(it.timeNanos) }
        valuesBefore.forEach { (lane, frame) -> project.valueTrack(lane).remove(frame.timeNanos) }
        val placedCamera = ArrayList<Long>()
        for ((index, key) in cameraBefore.withIndex()) {
            val source = cameraBefore.getOrNull(index + 1) ?: key
            val target = first + last - key.timeNanos
            camera.restore(
                CameraPathKey(
                    target,
                    key.position?.let { reversed(it, source.position, target) },
                    key.rotation?.let { reversed(it, source.rotation, target) },
                    key.fov?.let { reversed(it, source.fov, target) },
                )
            )
            placedCamera += target
        }
        val placedValues = ArrayList<ValueKey>()
        for (lane in valuesBefore.map { it.first }.distinct()) {
            val frames = valuesBefore.filter { it.first == lane }.map { it.second }.sortedBy { it.timeNanos }
            for ((index, frame) in frames.withIndex()) {
                val source = frames.getOrNull(index + 1) ?: frame
                val target = first + last - frame.timeNanos
                project.valueTrack(lane).set(
                    frame.copy(
                        timeNanos = target,
                        easing = source.easing.reversed(),
                        mode = source.mode,
                        handleIn = frame.handleOut,
                        handleOut = frame.handleIn
                    )
                )
                placedValues += ValueKey(lane, target)
            }
        }
        cameraAfter = placedCamera
        valuesAfter = placedValues
    }

    private fun <T> reversed(key: Keyframe<T>, source: Keyframe<T>?, target: Long): Keyframe<T> = key.copy(
        timeNanos = target,
        easing = source?.easing?.reversed() ?: key.easing,
        mode = source?.mode ?: key.mode,
        handleIn = key.handleOut,
        handleOut = key.handleIn,
    )

    override fun revert(project: EditorProject) {
        cameraAfter.forEach { project.camera.removeKeyframe(it) }
        valuesAfter.forEach { project.valueTrack(it.lane).remove(it.nanos) }
        cameraBefore.forEach { project.camera.restore(it) }
        valuesBefore.forEach { (lane, frame) -> project.valueTrack(lane).set(frame) }
    }
}
