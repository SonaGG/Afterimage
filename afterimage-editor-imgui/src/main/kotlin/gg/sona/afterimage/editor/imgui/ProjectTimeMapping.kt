package gg.sona.afterimage.editor.imgui

import gg.sona.afterimage.core.time.Nanos
import gg.sona.afterimage.editor.EditorProject
import gg.sona.afterimage.editor.LaneKind
import gg.sona.afterimage.render.TimeMapping

class ProjectTimeMapping private constructor(
    private val replayTimes: LongArray,
    private val outputTimes: LongArray,
) : TimeMapping {

    override val outputDurationNanos: Long get() = outputTimes.last()

    override fun replayNanosAt(outputNanos: Long): Long {
        if (outputNanos <= 0L) return replayTimes.first()
        if (outputNanos >= outputTimes.last()) return replayTimes.last()
        var low = 0
        var high = outputTimes.size - 1
        while (low < high) {
            val middle = (low + high + 1) ushr 1
            if (outputTimes[middle] <= outputNanos) low = middle else high = middle - 1
        }
        val next = minOf(low + 1, outputTimes.size - 1)
        val span = outputTimes[next] - outputTimes[low]
        if (span <= 0L) return replayTimes[low]
        val t = (outputNanos - outputTimes[low]).toDouble() / span
        return replayTimes[low] + ((replayTimes[next] - replayTimes[low]) * t).toLong()
    }

    private class Event(val nanos: Long, val freezeSeconds: Double?, val skipNanos: Long?)

    companion object {
        private val STEP = Nanos.ofMillis(25)

        fun build(project: EditorProject, startNanos: Long, endNanos: Long): TimeMapping? {
            val speedActive = project.laneEnabled(LaneKind.SPEED) && !project.speed.isEmpty
            val freezes =
                if (project.laneEnabled(LaneKind.FREEZE)) project.freeze.keyframes.filter { it.timeNanos in startNanos..endNanos } else emptyList()
            val timelapses =
                if (project.laneEnabled(LaneKind.TIMELAPSE)) project.timelapses.filter { it.nanos in startNanos..endNanos } else emptyList()
            if (!speedActive && freezes.isEmpty() && timelapses.isEmpty()) return null
            val events = (freezes.map { Event(it.timeNanos, it.value, null) } +
                    timelapses.map { Event(it.nanos, null, it.skipNanos) }).sortedBy { it.nanos }
            val replay = ArrayList<Long>()
            val output = ArrayList<Long>()
            var replayTime = startNanos
            var outputTime = 0L
            replay += replayTime
            output += 0
            var eventIndex = 0
            while (replayTime < endNanos) {
                val next = minOf(endNanos, replayTime + STEP)
                val event = events.getOrNull(eventIndex)
                if (event != null && event.nanos <= next) {
                    val speed = speedAt(project, replayTime)
                    outputTime += ((event.nanos - replayTime) / speed).toLong()
                    replayTime = event.nanos
                    replay += replayTime
                    output += outputTime
                    if (event.freezeSeconds != null) {
                        outputTime += (event.freezeSeconds * Nanos.PER_SECOND).toLong()
                    } else if (event.skipNanos != null) {
                        replayTime = minOf(endNanos, replayTime + maxOf(0L, event.skipNanos))
                    }
                    replay += replayTime
                    output += outputTime
                    eventIndex++
                    continue
                }
                val speed = speedAt(project, replayTime)
                outputTime += ((next - replayTime) / speed).toLong()
                replayTime = next
                replay += replayTime
                output += outputTime
            }
            return ProjectTimeMapping(replay.toLongArray(), output.toLongArray())
        }

        private fun speedAt(project: EditorProject, nanos: Long): Double =
            (project.speedAt(nanos) ?: 1.0).coerceIn(0.01, 64.0)
    }
}
