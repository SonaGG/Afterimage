package gg.sona.recast.replay.state.shadow

import gg.sona.recast.replay.state.camera.MotionSample
import java.util.*

class SampleTrack(capacity: Int = 16) {
    private val ring = Array(capacity) { MotionSample() }
    private val deltas = LongArray(capacity)
    private var count = 0
    private var head = 0

    var cadenceNanos: Long = DEFAULT_CADENCE_NANOS
        private set

    val size: Int get() = count

    fun latest(): MotionSample? = if (count == 0) null else ring[(head + count - 1) % ring.size]

    operator fun get(indexFromOldest: Int): MotionSample = ring[(head + indexFromOldest) % ring.size]

    fun push(nanos: Long, a: Double, b: Double, c: Double) {
        val last = latest()
        if (last != null && last.nanos == nanos) {
            last.set(nanos, a, b, c)
            return
        }
        val slot: MotionSample
        if (count < ring.size) {
            slot = ring[(head + count) % ring.size]
            count++
        } else {
            slot = ring[head]
            head = (head + 1) % ring.size
        }
        slot.set(nanos, a, b, c)
        updateCadence()
    }

    fun clear() {
        count = 0
        head = 0
        cadenceNanos = DEFAULT_CADENCE_NANOS
    }

    fun copyFrom(other: SampleTrack) {
        clear()
        val start = (other.count - ring.size).coerceAtLeast(0)
        for (index in start until other.count) {
            val sample = other[index]
            push(sample.nanos, sample.a, sample.b, sample.c)
        }
    }

    private fun updateCadence() {
        var found = 0
        for (index in 1 until count) {
            val delta = this[index].nanos - this[index - 1].nanos
            if (delta > 0L) deltas[found++] = delta
        }
        if (found == 0) {
            cadenceNanos = DEFAULT_CADENCE_NANOS
            return
        }
        Arrays.sort(deltas, 0, found)
        cadenceNanos = deltas[found / 2].coerceIn(MIN_CADENCE_NANOS, MAX_CADENCE_NANOS)
    }

    companion object {
        const val DEFAULT_CADENCE_NANOS = 100_000_000L
        const val MIN_CADENCE_NANOS = 50_000_000L
        const val MAX_CADENCE_NANOS = 250_000_000L
    }
}
