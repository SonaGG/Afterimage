package gg.sona.afterimage.mc.common

import gg.sona.afterimage.camera.CameraPose
import gg.sona.afterimage.render.PcmClip
import gg.sona.afterimage.render.SoundEvent

abstract class SoundCaptureBase {
    @Volatile
    var muted: Boolean = false

    @Volatile
    var capturing: Boolean = false

    var outputNanos: Long = 0L
    var listener: () -> CameraPose? = { null }

    private val events = ArrayList<SoundEvent>()
    val clips = HashMap<String, PcmClip?>()

    val size: Int get() = events.size

    fun reset() {
        events.clear()
        clips.clear()
        outputNanos = 0L
    }

    fun drain(): List<SoundEvent> {
        val result = ArrayList(events)
        events.clear()
        return result
    }

    fun clip(location: String): PcmClip? = clips.getOrPut(location) { decode(location) }

    protected abstract fun decode(location: String): PcmClip?

    protected fun record(location: String, x: Double, y: Double, z: Double, volume: Float, pitch: Float, attenuated: Boolean, rolloff: Float) {
        if (events.size >= MAX_EVENTS) return
        val pose = listener()
        val right = pose?.rotation?.right()
        events += SoundEvent(
            outputNanos,
            location,
            x,
            y,
            z,
            volume,
            pitch,
            attenuated && pose != null,
            rolloff,
            pose?.position?.x ?: 0.0,
            pose?.position?.y ?: 0.0,
            pose?.position?.z ?: 0.0,
            right?.x ?: 1.0,
            right?.y ?: 0.0,
            right?.z ?: 0.0,
        )
    }

    private companion object {
        const val MAX_EVENTS = 200_000
    }
}
