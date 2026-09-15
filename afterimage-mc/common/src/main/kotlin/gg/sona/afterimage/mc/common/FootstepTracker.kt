package gg.sona.afterimage.mc.common

import kotlin.math.floor
import kotlin.math.sqrt

class FootstepTracker(private val probe: FootstepProbe, private val swimSound: String) {
    private var lastX = 0.0
    private var lastY = 0.0
    private var lastZ = 0.0
    private var hasLast = false
    private var walked = 0.0
    private var nextStep = 1

    fun reset() {
        hasLast = false
        walked = 0.0
        nextStep = 1
    }

    fun reposition(x: Double, y: Double, z: Double) {
        lastX = x
        lastY = y
        lastZ = z
        hasLast = true
    }

    fun move(x: Double, y: Double, z: Double, onGround: Boolean, sneaking: Boolean, riding: Boolean, nanos: Long): FootstepSound? {
        if (!hasLast) {
            reposition(x, y, z)
            return null
        }
        val dx = x - lastX
        val dy = y - lastY
        val dz = z - lastZ
        reposition(x, y, z)
        if (riding || (sneaking && onGround)) return null
        if (dx * dx + dz * dz > TELEPORT_DISTANCE_SQUARED) return null
        val blockX = floor(x).toInt()
        var blockY = floor(y - 0.2).toInt()
        val blockZ = floor(z).toInt()
        var block = probe.blockAt(blockX, blockY, blockZ)
        if (block.isAir) {
            val below = probe.blockAt(blockX, blockY - 1, blockZ)
            if (below.isFenceLike) {
                block = below
                blockY--
            }
        }
        val vertical = if (block.isClimbable) dy else 0.0
        walked += sqrt(dx * dx + vertical * vertical + dz * dz) * 0.6
        if (walked <= nextStep || block.isAir) return null
        nextStep = walked.toInt() + 1
        if (probe.blockAt(floor(x).toInt(), floor(y + 0.5).toInt(), floor(z).toInt()).isWater) {
            val volume = (sqrt(dx * dx * 0.2 + dy * dy + dz * dz * 0.2) * 0.35).toFloat().coerceAtMost(1f)
            return FootstepSound(swimSound, volume, 1f + noise(nanos) * 0.4f)
        }
        if (block.isLiquid) return null
        val above = probe.blockAt(blockX, blockY + 1, blockZ)
        val source = if (above.isSnowLayer) above else block
        val sound = source.sound ?: return null
        return FootstepSound(sound, source.volume * 0.15f, source.pitch)
    }

    private fun noise(nanos: Long): Float = ((nanos ushr 16) % 41L - 20L) / 100f

    private companion object {
        const val TELEPORT_DISTANCE_SQUARED = 4.0
    }
}
