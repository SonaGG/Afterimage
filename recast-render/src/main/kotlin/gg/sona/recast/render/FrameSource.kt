package gg.sona.recast.render

import gg.sona.recast.camera.CameraPose

interface FrameSource {
    fun prepare(settings: ExportSettings)

    fun beginFrame(index: Long, outputNanos: Long) = Unit

    fun renderPass(nanos: Long, pose: CameraPose, pass: Int)

    fun compose(into: ByteArray, depthInto: FloatArray?, sample: Int, samples: Int): Long

    fun flush(into: ByteArray, depthInto: FloatArray?): Long = NO_FRAME

    fun warnings(): List<String> = emptyList()

    fun release()

    companion object {
        const val NO_FRAME = -1L
    }
}
