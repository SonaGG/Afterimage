package gg.sona.recast.render


data class MotionBlur(val samples: Int = 1, val shutter: Double = 0.5) {
    val enabled: Boolean get() = samples > 1

    companion object {
        val OFF = MotionBlur()
    }
}
