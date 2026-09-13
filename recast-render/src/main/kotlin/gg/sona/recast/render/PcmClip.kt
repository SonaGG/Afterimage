package gg.sona.recast.render

import gg.sona.recast.core.time.Nanos

class PcmClip(val samples: FloatArray, val sampleRate: Int) {
    val durationNanos: Long get() = samples.size * Nanos.PER_SECOND / sampleRate
}
