package gg.sona.recast.replay.state.camera

import gg.sona.recast.core.time.Nanos

class CameraSamples(private val capacity: Int = 96) {

    private val samples = ArrayList<CameraSample>(capacity)
    private var smoothedInterval = 0L

    val size: Int get() = samples.size

    val latest: CameraSample? get() = samples.lastOrNull()

    fun push(sample: CameraSample) {
        val found = samples.binarySearchBy(sample.nanos) { it.nanos }
        if (found >= 0) {
            samples[found] = sample
            return
        }
        val insertAt = -found - 1
        val last = samples.lastOrNull()
        if (insertAt == samples.size && last != null) {
            val delta = (sample.nanos - last.nanos).coerceIn(MIN_INTERVAL, MAX_INTERVAL)
            smoothedInterval = if (smoothedInterval == 0L) delta else smoothedInterval + (delta - smoothedInterval) / 8
        }
        samples.add(insertAt, sample)
        while (samples.size > capacity) samples.removeAt(0)
    }

    fun copyFrom(other: CameraSamples) {
        samples.clear()
        samples.addAll(other.samples)
        smoothedInterval = other.smoothedInterval
    }

    fun dropAfter(nanos: Long) {
        while (samples.isNotEmpty() && samples.last().nanos > nanos) samples.removeAt(samples.size - 1)
    }

    fun before(nanos: Long): CameraSample? = samples.lastOrNull { it.nanos <= nanos } ?: samples.firstOrNull()

    fun after(nanos: Long): CameraSample? = samples.firstOrNull { it.nanos > nanos }

    fun intervalEstimate(): Long = if (smoothedInterval == 0L) DEFAULT_INTERVAL else smoothedInterval

    fun blended(nanos: Long, lookBehind: Boolean = true): BlendedCameraFrame? {
        if (samples.isEmpty()) return null
        val target = if (lookBehind && after(nanos) == null) nanos - (intervalEstimate() * 3 / 2).coerceIn(
            MIN_LAG,
            MAX_LAG
        ) else nanos
        val before = before(target) ?: return null
        val after = after(target)
        if (after == null || after.nanos <= before.nanos || target <= before.nanos) {
            return BlendedCameraFrame(before.modelView, before.fov, before.position, before.hand, true)
        }
        val t = ((target - before.nanos).toDouble() / (after.nanos - before.nanos)).coerceIn(0.0, 1.0).toFloat()
        val view = lerp(before.modelView, after.modelView, t)
        val hand = when {
            before.hand != null && after.hand != null -> lerp(before.hand, after.hand, t)
            else -> before.hand ?: after.hand
        }
        val position = when {
            before.position != null && after.position != null -> DoubleArray(3) { before.position[it] + (after.position[it] - before.position[it]) * t }
            else -> before.position ?: after.position
        }
        return BlendedCameraFrame(view, before.fov + (after.fov - before.fov) * t, position, hand, false)
    }

    fun clear() {
        samples.clear()
        smoothedInterval = 0L
    }

    fun all(): List<CameraSample> = samples.toList()

    private fun lerp(a: FloatArray, b: FloatArray, t: Float): FloatArray =
        FloatArray(a.size) { a[it] + (b[it] - a[it]) * t }

    private companion object {
        const val DEFAULT_INTERVAL = Nanos.PER_MILLI * 16
        const val MIN_INTERVAL = Nanos.PER_MILLI * 2
        const val MAX_INTERVAL = Nanos.PER_MILLI * 80
        const val MIN_LAG = Nanos.PER_MILLI * 8
        const val MAX_LAG = Nanos.PER_MILLI * 120
    }
}
