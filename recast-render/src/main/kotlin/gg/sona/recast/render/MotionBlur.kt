package gg.sona.recast.render


data class MotionBlur(val samples: Int = 1, val shutter: Double = 0.5) {
    val enabled: Boolean get() = samples > 1

    val sampleCount: Int get() = samples.coerceIn(1, MAX_SAMPLES)

    fun offsetNanos(sample: Int, intervalNanos: Long): Long {
        val count = sampleCount
        if (count == 1) return 0L
        val open = shutter.coerceIn(MIN_SHUTTER, 1.0)
        return (((sample + 0.5) / count - 0.5) * open * intervalNanos).toLong()
    }

    companion object {
        const val MAX_SAMPLES = 64
        const val MIN_SHUTTER = 0.05
        val OFF = MotionBlur()
    }
}
