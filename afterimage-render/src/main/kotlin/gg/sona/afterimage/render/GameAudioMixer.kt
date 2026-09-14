package gg.sona.afterimage.render

import gg.sona.afterimage.core.time.Nanos
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

object GameAudioMixer {

    const val SAMPLE_RATE = 48000
    private const val PAN_STRENGTH = 0.75

    fun gainFor(event: SoundEvent): Pair<Float, Float> {
        var gain = event.volume.coerceIn(0f, 1f).toDouble()
        var pan = 0.0
        if (event.attenuate) {
            val dx = event.x - event.listenerX
            val dy = event.y - event.listenerY
            val dz = event.z - event.listenerZ
            val distance = sqrt(dx * dx + dy * dy + dz * dz)
            val rolloff = max(event.rolloff, 0.001f).toDouble()
            gain *= if (distance >= rolloff) 0.0 else 1.0 - distance / rolloff
            if (distance > 0.05) {
                val dot = (dx * event.rightX + dy * event.rightY + dz * event.rightZ) / distance
                pan = (dot * PAN_STRENGTH).coerceIn(-1.0, 1.0)
            }
        }
        val left = sqrt((1.0 - pan) / 2.0) * gain
        val right = sqrt((1.0 + pan) / 2.0) * gain
        return left.toFloat() to right.toFloat()
    }

    fun mix(
        events: List<SoundEvent>,
        durationNanos: Long,
        clips: (String) -> PcmClip?,
        masterGain: Float = 1f,
        sampleRate: Int = SAMPLE_RATE
    ): AudioMix {
        val frames = ((durationNanos * sampleRate) / Nanos.PER_SECOND).toInt().coerceAtLeast(1)
        val left = FloatArray(frames)
        val right = FloatArray(frames)
        val cache = HashMap<String, PcmClip?>()
        for (event in events) {
            val clip = cache.getOrPut(event.clip) { clips(event.clip) } ?: continue
            val (gainLeft, gainRight) = gainFor(event)
            if (gainLeft <= 0f && gainRight <= 0f) continue
            val start = ((event.outputNanos * sampleRate) / Nanos.PER_SECOND).toInt()
            if (start >= frames) continue
            val rate = event.pitch.coerceIn(0.5f, 2f).toDouble() * clip.sampleRate / sampleRate
            val source = clip.samples
            val outputLength = min(frames - start, ((source.size - 1) / rate).toInt())
            var position = 0.0
            for (i in 0 until outputLength) {
                val index = position.toInt()
                val fraction = (position - index).toFloat()
                val sample = source[index] + (source[min(index + 1, source.size - 1)] - source[index]) * fraction
                left[start + i] += sample * gainLeft
                right[start + i] += sample * gainRight
                position += rate
            }
        }
        var peak = 0f
        for (i in 0 until frames) {
            left[i] *= masterGain
            right[i] *= masterGain
            peak = max(peak, max(abs(left[i]), abs(right[i])))
        }
        if (peak > 1f) {
            val scale = 1f / peak
            for (i in 0 until frames) {
                left[i] *= scale
                right[i] *= scale
            }
        }
        return AudioMix(left, right, sampleRate)
    }

    fun writeWav(mix: AudioMix, path: Path) {
        val dataBytes = mix.frames * 4
        val buffer = ByteBuffer.allocate(dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until mix.frames) {
            buffer.putShort(toPcm(mix.left[i]))
            buffer.putShort(toPcm(mix.right[i]))
        }
        DataOutputStream(Files.newOutputStream(path).buffered()).use { out ->
            out.writeBytes("RIFF")
            out.writeInt(Integer.reverseBytes(36 + dataBytes))
            out.writeBytes("WAVE")
            out.writeBytes("fmt ")
            out.writeInt(Integer.reverseBytes(16))
            out.writeShort(java.lang.Short.reverseBytes(1).toInt())
            out.writeShort(java.lang.Short.reverseBytes(2).toInt())
            out.writeInt(Integer.reverseBytes(mix.sampleRate))
            out.writeInt(Integer.reverseBytes(mix.sampleRate * 4))
            out.writeShort(java.lang.Short.reverseBytes(4).toInt())
            out.writeShort(java.lang.Short.reverseBytes(16).toInt())
            out.writeBytes("data")
            out.writeInt(Integer.reverseBytes(dataBytes))
            out.write(buffer.array())
        }
    }

    fun decodePcm16(bytes: ByteArray, channels: Int, sampleRate: Int, bigEndian: Boolean): PcmClip {
        val frameCount = bytes.size / (2 * channels)
        val samples = FloatArray(frameCount)
        val buffer = ByteBuffer.wrap(bytes).order(if (bigEndian) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until frameCount) {
            var sum = 0f
            for (c in 0 until channels) sum += buffer.getShort((i * channels + c) * 2) / 32768f
            samples[i] = sum / channels
        }
        return PcmClip(samples, sampleRate)
    }

    private fun toPcm(value: Float): Short = (value.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
}
