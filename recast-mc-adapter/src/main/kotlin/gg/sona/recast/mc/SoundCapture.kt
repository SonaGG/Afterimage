package gg.sona.recast.mc

import gg.sona.recast.camera.CameraPose
import gg.sona.recast.mc.mixin.SoundEngineAccessor
import gg.sona.recast.render.GameAudioMixer
import gg.sona.recast.render.PcmClip
import gg.sona.recast.render.SoundEvent
import net.minecraft.client.Minecraft
import net.minecraft.client.sound.instance.SoundInstance
import net.minecraft.client.sound.system.SoundEngine
import net.minecraft.client.sound.system.SoundManager
import net.minecraft.resource.Identifier
import org.apache.logging.log4j.LogManager

class SoundCapture(private val minecraft: Minecraft) {

    private val logger = LogManager.getLogger("Recast")

    @Volatile
    var muted: Boolean = false

    @Volatile
    var capturing: Boolean = false

    var outputNanos: Long = 0L
    var listener: () -> CameraPose? = { null }

    private val events = ArrayList<SoundEvent>()
    val clips = HashMap<String, PcmClip?>()

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

    val size: Int get() = events.size

    fun onPlay(engine: SoundEngine, instance: SoundInstance): Boolean {
        if (!muted) return false
        if (!capturing || instance.isLooping) return true
        try {
            val accessor = engine as SoundEngineAccessor
            val pool = accessor.`recast$manager`().get(instance.location) ?: return true
            val sound = pool.get()
            if (sound === SoundManager.MISSING_SOUND || sound.isStream) return true
            val volume = accessor.`recast$volume`(instance, sound, pool.category)
            if (volume <= 0f) return true
            val pitch = accessor.`recast$pitch`(instance, sound)
            val rolloff = 16f * maxOf(1f, instance.volume)
            val pose = listener()
            val right = pose?.rotation?.right()
            if (events.size < MAX_EVENTS) {
                events += SoundEvent(
                    outputNanos,
                    sound.location.toString(),
                    instance.x.toDouble(),
                    instance.y.toDouble(),
                    instance.z.toDouble(),
                    volume,
                    pitch,
                    instance.attenuationType == SoundInstance.Attenuation.LINEAR && pose != null,
                    rolloff,
                    pose?.position?.x ?: 0.0,
                    pose?.position?.y ?: 0.0,
                    pose?.position?.z ?: 0.0,
                    right?.x ?: 1.0,
                    right?.y ?: 0.0,
                    right?.z ?: 0.0,
                )
            }
        } catch (error: Throwable) {
            logger.warn("Recast could not capture a sound for export", error)
        }
        return true
    }

    fun clip(location: String): PcmClip? = clips.getOrPut(location) { decode(location) }

    private fun decode(location: String): PcmClip? = try {
        val url = SoundEngineAccessor.`recast$soundUrl`(Identifier(location))
        val codec = paulscode.sound.codecs.CodecJOrbis()
        if (!codec.initialize(url)) {
            null
        } else {
            val buffer = codec.readAll()
            codec.cleanup()
            if (buffer == null) null else {
                val format = buffer.audioFormat
                GameAudioMixer.decodePcm16(
                    buffer.audioData,
                    format.channels,
                    format.sampleRate.toInt(),
                    format.isBigEndian
                )
            }
        }
    } catch (error: Throwable) {
        logger.warn("Recast could not decode sound {}", location, error)
        null
    }

    private companion object {
        const val MAX_EVENTS = 200_000
    }
}
