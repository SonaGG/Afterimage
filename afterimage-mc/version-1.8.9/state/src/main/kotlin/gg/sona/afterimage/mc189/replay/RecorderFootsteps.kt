package gg.sona.afterimage.mc189.replay

import kotlin.math.floor
import kotlin.math.sqrt

class RecorderFootsteps(private val blockState: (Int, Int, Int) -> Int) {
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

    fun move(
        x: Double,
        y: Double,
        z: Double,
        onGround: Boolean,
        sneaking: Boolean,
        riding: Boolean,
        nanos: Long
    ): FootstepSound? {
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
        var state = blockState(blockX, blockY, blockZ)
        if (state == 0) {
            val below = blockState(blockX, blockY - 1, blockZ)
            if ((below shr 4) in FENCE_LIKE) {
                state = below
                blockY--
            }
        }
        val id = state shr 4
        val vertical = if (id == LADDER) dy else 0.0
        walked += sqrt(dx * dx + vertical * vertical + dz * dz) * 0.6
        if (walked <= nextStep || id == 0) return null
        nextStep = walked.toInt() + 1
        if (inWater(x, y, z)) {
            val volume = (sqrt(dx * dx * 0.2 + dy * dy + dz * dz * 0.2) * 0.35).toFloat().coerceAtMost(1f)
            return FootstepSound("game.player.swim", volume, 1f + noise(nanos) * 0.4f)
        }
        if (id in LIQUIDS) return null
        val step =
            if ((blockState(blockX, blockY + 1, blockZ) shr 4) == SNOW_LAYER) StepSound.SNOW else StepSound.of(id)
        return FootstepSound(step.sound, step.volume * 0.15f, step.pitch)
    }

    private fun inWater(x: Double, y: Double, z: Double): Boolean =
        (blockState(floor(x).toInt(), floor(y + 0.5).toInt(), floor(z).toInt()) shr 4) in WATER

    private fun noise(nanos: Long): Float = ((nanos ushr 16) % 41L - 20L) / 100f

    private companion object {
        const val LADDER = 65
        const val SNOW_LAYER = 78
        const val TELEPORT_DISTANCE_SQUARED = 4.0
        val LIQUIDS = setOf(8, 9, 10, 11)
        val WATER = setOf(8, 9)
        val FENCE_LIKE = setOf(85, 113, 139, 107, 183, 184, 185, 186, 187, 188, 189, 190, 191, 192)
    }
}
