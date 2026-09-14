package gg.sona.afterimage.render

import gg.sona.afterimage.core.time.Nanos

class PcmClip(val samples: FloatArray, val sampleRate: Int) {
    val durationNanos: Long get() = samples.size * Nanos.PER_SECOND / sampleRate
}
