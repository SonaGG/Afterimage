package gg.sona.afterimage.mc26.replay

import gg.sona.afterimage.mc.common.SoundCaptureBase
import gg.sona.afterimage.mc26.mixin.SoundEngineAccessor
import gg.sona.afterimage.render.GameAudioMixer
import gg.sona.afterimage.render.PcmClip
import net.minecraft.client.Minecraft
import net.minecraft.client.resources.sounds.SoundInstance
import net.minecraft.client.sounds.JOrbisAudioStream
import net.minecraft.client.sounds.SoundEngine
import net.minecraft.client.sounds.SoundManager
import net.minecraft.resources.Identifier
import org.slf4j.LoggerFactory

class SoundCapture26(private val minecraft: Minecraft) : SoundCaptureBase() {
    private val logger = LoggerFactory.getLogger("Afterimage")

    var settling: () -> Boolean = { false }

    fun onPlay(engine: SoundEngine, instance: SoundInstance): Boolean {
        if (!muted && !settling()) return false
        if (!capturing || instance.isLooping) return true
        try {
            val accessor = engine as SoundEngineAccessor
            instance.getOrResolve(accessor.afterimage_soundManager()) ?: return true
            val sound = instance.sound
            if (sound == null || sound === SoundManager.EMPTY_SOUND || sound === SoundManager.INTENTIONALLY_EMPTY_SOUND || sound.shouldStream()) return true
            val volume = accessor.afterimage_calculateVolume(instance)
            if (volume <= 0f) return true
            val pitch = accessor.afterimage_calculatePitch(instance)
            val rolloff = sound.getAttenuationDistance(instance.volume)
            record(sound.path.toString(), instance.x, instance.y, instance.z, volume, pitch, instance.attenuation == SoundInstance.Attenuation.LINEAR && !instance.isRelative, rolloff)
        } catch (error: Throwable) {
            logger.warn("Afterimage could not capture a sound for export", error)
        }
        return true
    }

    override fun decode(location: String): PcmClip? = try {
        val resource = minecraft.resourceManager.getResource(Identifier.parse(location)).orElse(null)
        if (resource == null) null else resource.open().use { input ->
            JOrbisAudioStream(input).use { stream ->
                val buffer = stream.readAll()
                val format = stream.format
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                GameAudioMixer.decodePcm16(bytes, format.channels, format.sampleRate.toInt(), format.isBigEndian)
            }
        }
    } catch (error: Throwable) {
        logger.warn("Afterimage could not decode sound {}", location, error)
        null
    }
}
