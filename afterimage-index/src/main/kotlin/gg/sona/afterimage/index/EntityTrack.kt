package gg.sona.afterimage.index

import gg.sona.afterimage.replay.state.shadow.EntityKind
import java.util.*
import kotlin.math.sqrt

class EntityTrack(
    val entityId: Int,
    val kind: EntityKind,
    val type: Int,
    val uuid: UUID?,
    val name: String?,
    val isRecorder: Boolean,
    val shooterId: Int,
    val firstTick: Int,
    val lastTick: Int,
    val x: DoubleArray,
    val y: DoubleArray,
    val z: DoubleArray,
    val yaw: FloatArray,
    val pitch: FloatArray,
    val headYaw: FloatArray,
    val health: FloatArray,
    val held: ShortArray,
    val armor: Array<ShortArray>,
    val flags: ByteArray,
    val vehicle: IntArray,
) {
    val isPlayer: Boolean get() = kind == EntityKind.PLAYER

    val length: Int get() = lastTick - firstTick + 1

    val label: String get() = name ?: EntityNames.of(kind, type, entityId)

    fun covers(tick: Int): Boolean = tick in firstTick..lastTick

    fun index(tick: Int): Int = tick - firstTick

    fun speedAt(tick: Int, tickSeconds: Double): Double {
        val index = index(tick)
        if (index !in 1..<length) return 0.0
        val dx = x[index] - x[index - 1]
        val dy = y[index] - y[index - 1]
        val dz = z[index] - z[index - 1]
        return sqrt(dx * dx + dy * dy + dz * dz) / tickSeconds
    }

    fun flag(tick: Int, mask: Int): Boolean = flags[index(tick)].toInt() and mask != 0

    companion object {
        const val FLAG_ON_FIRE = 0x01
        const val FLAG_SNEAKING = 0x02
        const val FLAG_SPRINTING = 0x08
        const val FLAG_USING = 0x10
        const val FLAG_INVISIBLE = 0x20
        const val FLAG_ON_GROUND = 0x40
    }
}
