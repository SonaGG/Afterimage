package gg.sona.recast.editor.commands

import gg.sona.recast.camera.CameraPathKey
import gg.sona.recast.camera.track.Keyframe
import gg.sona.recast.editor.EditorCommand
import gg.sona.recast.editor.EditorProject
import gg.sona.recast.editor.ValueKey
import gg.sona.recast.editor.ValueLane
import kotlin.math.roundToLong

class ScaleKeyframes(
    private val times: Set<Long>,
    private val valueKeys: Set<ValueKey>,
    private val pivotNanos: Long,
    private val factor: Double,
) : EditorCommand {

    private var cameraBefore: List<CameraPathKey> = emptyList()
    private var valuesBefore: List<Pair<ValueLane, Keyframe<Double>>> = emptyList()
    private var cameraAfter: List<Long> = emptyList()
    private var valuesAfter: List<ValueKey> = emptyList()
    private var displacedCamera: List<CameraPathKey> = emptyList()
    private var displacedValues: List<Pair<ValueLane, Keyframe<Double>>> = emptyList()

    override val label: String get() = "Stretch keyframes"

    val resultTimes: Set<Long> get() = cameraAfter.toSet()

    val resultValueKeys: Set<ValueKey> get() = valuesAfter.toSet()

    fun scaled(nanos: Long): Long = maxOf(0L, (pivotNanos + (nanos - pivotNanos) * factor).roundToLong())

    override fun apply(project: EditorProject) {
        val camera = project.camera
        cameraBefore = times.mapNotNull { camera.snapshot(it) }
        valuesBefore =
            valueKeys.mapNotNull { key -> project.valueTrack(key.lane).at(key.nanos)?.let { key.lane to it } }
        cameraBefore.forEach { camera.removeKeyframe(it.timeNanos) }
        valuesBefore.forEach { (lane, frame) -> project.valueTrack(lane).remove(frame.timeNanos) }
        val displacedC = ArrayList<CameraPathKey>()
        val placedC = ArrayList<Long>()
        for (key in cameraBefore) {
            val target = scaled(key.timeNanos)
            if (target in placedC) continue
            camera.snapshot(target)?.let { displacedC += it }
            camera.restore(key.at(target))
            placedC += target
        }
        val displacedV = ArrayList<Pair<ValueLane, Keyframe<Double>>>()
        val placedV = ArrayList<ValueKey>()
        for ((lane, frame) in valuesBefore) {
            val target = ValueKey(lane, scaled(frame.timeNanos))
            if (target in placedV) continue
            val track = project.valueTrack(lane)
            track.at(target.nanos)?.let { displacedV += lane to it }
            track.set(frame.copy(timeNanos = target.nanos))
            placedV += target
        }
        cameraAfter = placedC
        valuesAfter = placedV
        displacedCamera = displacedC
        displacedValues = displacedV
    }

    override fun revert(project: EditorProject) {
        val camera = project.camera
        cameraAfter.forEach { camera.removeKeyframe(it) }
        valuesAfter.forEach { project.valueTrack(it.lane).remove(it.nanos) }
        displacedCamera.forEach { camera.restore(it) }
        displacedValues.forEach { (lane, frame) -> project.valueTrack(lane).set(frame) }
        cameraBefore.forEach { camera.restore(it) }
        valuesBefore.forEach { (lane, frame) -> project.valueTrack(lane).set(frame) }
    }

    override fun mergeWith(next: EditorCommand): EditorCommand? {
        if (next !is ScaleKeyframes || next.pivotNanos != pivotNanos) return null
        if (next.times != resultTimes || next.valueKeys != resultValueKeys) return null
        return ScaleKeyframes(times, valueKeys, pivotNanos, factor * next.factor).also {
            it.cameraBefore = cameraBefore
            it.valuesBefore = valuesBefore
            it.cameraAfter = next.cameraAfter
            it.valuesAfter = next.valuesAfter
            it.displacedCamera = displacedCamera + next.displacedCamera
            it.displacedValues = displacedValues + next.displacedValues
        }
    }
}
