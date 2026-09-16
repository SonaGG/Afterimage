package gg.sona.afterimage.mc189.replay

import gg.sona.afterimage.mc189.mixin.SoundEngineAccessor
import gg.sona.afterimage.render.GameAudioMixer
import gg.sona.afterimage.render.PcmClip
import gg.sona.afterimage.mc.common.SoundCaptureBase
import net.minecraft.client.Minecraft
import net.minecraft.client.sound.instance.SoundInstance
import net.minecraft.client.sound.system.SoundEngine
import net.minecraft.client.sound.system.SoundManager
import net.minecraft.resource.Identifier
import org.apache.logging.log4j.LogManager

class SoundCapture(private val minecraft: Minecraft) : SoundCaptureBase() {
    private val logger = LogManager.getLogger("Afterimage")

    fun onPlay(engine: SoundEngine, instance: SoundInstance): Boolean {
        if (!muted) return false
        if (!capturing || instance.isLooping) return true
        try {
            val accessor = engine as SoundEngineAccessor
            val pool = accessor.`afterimage$manager`().get(instance.location) ?: return true
            val sound = pool.get()
            if (sound === SoundManager.MISSING_SOUND || sound.isStream) return true
            val volume = accessor.`afterimage$volume`(instance, sound, pool.category)
            if (volume <= 0f) return true
            val pitch = accessor.`afterimage$pitch`(instance, sound)
            val rolloff = 16f * maxOf(1f, instance.volume)
            record(sound.location.toString(), instance.x.toDouble(), instance.y.toDouble(), instance.z.toDouble(), volume, pitch, instance.attenuationType == SoundInstance.Attenuation.LINEAR, rolloff)
        } catch (error: Throwable) {
            logger.warn("Afterimage could not capture a sound for export", error)
        }
        return true
    }


    override fun decode(location: String): PcmClip? = try {
        val url = SoundEngineAccessor.`afterimage$soundUrl`(Identifier(location))
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
        logger.warn("Afterimage could not decode sound {}", location, error)
        null
    }

}
