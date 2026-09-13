package gg.sona.recast.render


class AudioMix(val left: FloatArray, val right: FloatArray, val sampleRate: Int) {
    val frames: Int get() = left.size
}
