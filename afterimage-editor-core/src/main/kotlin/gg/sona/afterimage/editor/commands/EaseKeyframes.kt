package gg.sona.afterimage.editor.commands

import gg.sona.afterimage.camera.Easing
import gg.sona.afterimage.editor.EditorCommand
import gg.sona.afterimage.editor.EditorProject
import gg.sona.afterimage.editor.ValueKey

class EaseKeyframes(
    private val times: Set<Long>,
    private val valueKeys: Set<ValueKey>,
    private val departure: Pair<Double, Double>?,
    private val arrival: Pair<Double, Double>?,
    override val label: String = "Ease keyframes",
) : EditorCommand {

    private val previousCamera = HashMap<Long, Easing>()
    private val previousValues = HashMap<ValueKey, Easing>()

    override fun apply(project: EditorProject) {
        previousCamera.clear()
        previousValues.clear()
        val camera = project.camera
        for (time in times) {
            val current = camera.keyframeAt(time) ?: continue
            if (departure != null && camera.nextKeyframeTime(time) != null) {
                previousCamera.putIfAbsent(time, current.easing)
                camera.setEasing(
                    time,
                    (camera.keyframeAt(time)?.easing ?: current.easing).withOut(departure.first, departure.second)
                )
            }
            if (arrival != null) {
                val previous = camera.previousKeyframeTime(time) ?: continue
                val before = camera.keyframeAt(previous) ?: continue
                previousCamera.putIfAbsent(previous, before.easing)
                camera.setEasing(previous, before.easing.withIn(arrival.first, arrival.second))
            }
        }
        for (key in valueKeys) {
            val track = project.valueTrack(key.lane)
            val current = track.at(key.nanos) ?: continue
            if (departure != null && track.next(key.nanos) != null) {
                previousValues.putIfAbsent(key, current.easing)
                track.update(key.nanos) { it.copy(easing = it.easing.withOut(departure.first, departure.second)) }
            }
            if (arrival != null) {
                val previous = track.previous(key.nanos) ?: continue
                val previousKey = ValueKey(key.lane, previous.timeNanos)
                previousValues.putIfAbsent(previousKey, previous.easing)
                track.update(previous.timeNanos) { it.copy(easing = it.easing.withIn(arrival.first, arrival.second)) }
            }
        }
    }

    override fun revert(project: EditorProject) {
        for ((time, easing) in previousCamera) project.camera.setEasing(time, easing)
        for ((key, easing) in previousValues) project.valueTrack(key.lane)
            .update(key.nanos) { it.copy(easing = easing) }
    }

    companion object {
        val EASE_OUT_HANDLE = 1.0 / 3.0 to 0.0
        val EASE_IN_HANDLE = 2.0 / 3.0 to 1.0

        val LINEAR_OUT_HANDLE = 1.0 / 3.0 to 1.0 / 3.0
        val LINEAR_IN_HANDLE = 2.0 / 3.0 to 2.0 / 3.0

        fun easyEase(times: Set<Long>, keys: Set<ValueKey>) =
            EaseKeyframes(times, keys, EASE_OUT_HANDLE, EASE_IN_HANDLE, "Easy ease")

        fun easeIn(times: Set<Long>, keys: Set<ValueKey>) =
            EaseKeyframes(times, keys, null, EASE_IN_HANDLE, "Ease into keyframes")

        fun easeOut(times: Set<Long>, keys: Set<ValueKey>) =
            EaseKeyframes(times, keys, EASE_OUT_HANDLE, null, "Ease out of keyframes")

        fun linear(times: Set<Long>, keys: Set<ValueKey>) =
            EaseKeyframes(times, keys, LINEAR_OUT_HANDLE, LINEAR_IN_HANDLE, "Linear keyframes")
    }
}
