package gg.sona.recast.replay.state.shadow

import gg.sona.recast.core.collect.IntObjectMap
import gg.sona.recast.net.nbt.NbtCompound
import gg.sona.recast.protocol.EntityAttribute
import gg.sona.recast.protocol.ItemStack
import gg.sona.recast.protocol.MetadataEntry
import gg.sona.recast.protocol.Protocol
import gg.sona.recast.replay.state.interpolation.Interpolation
import java.util.*

class ShadowEntity(val id: Int, val kind: EntityKind, val spawnedAtNanos: Long) {
    var type: Int = 0
    var objectData: Int = 0
    var uuid: UUID? = null
    var fixedX: Int = 0
    var fixedY: Int = 0
    var fixedZ: Int = 0
    var yaw: Byte = 0
    var pitch: Byte = 0
    var headYaw: Byte = 0
    var velocityX: Int = 0
    var velocityY: Int = 0
    var velocityZ: Int = 0
    var onGround: Boolean = false
    var currentItem: Int = 0
    var paintingTitle: String = ""
    var paintingPosition: Long = 0L
    var paintingFacing: Int = 0
    var orbCount: Int = 0
    var vehicleId: Int = -1
    var leashHolderId: Int = -1
    var nbt: NbtCompound? = null
    var lastStatus: Int = -1
    var dead: Boolean = false
    var deadAtNanos: Long = Long.MIN_VALUE
    var hurtAtNanos: Long = Long.MIN_VALUE
    var swingAtNanos: Long = Long.MIN_VALUE

    val metadata = IntObjectMap<MetadataEntry>()
    val equipment = arrayOfNulls<ItemStack>(5)
    val effects = IntObjectMap<ShadowEffect>()
    val attributes = LinkedHashMap<String, EntityAttribute>()
    val history = MotionHistory()

    val x: Double get() = Protocol.fromFixed(fixedX)
    val y: Double get() = Protocol.fromFixed(fixedY)
    val z: Double get() = Protocol.fromFixed(fixedZ)
    val yawDegrees: Float get() = Protocol.fromAngle(yaw)
    val pitchDegrees: Float get() = Protocol.fromAngle(pitch)
    val headYawDegrees: Float get() = Protocol.fromAngle(headYaw)

    val isPlayer: Boolean get() = kind == EntityKind.PLAYER

    fun visibleAt(nanos: Long): Boolean = !dead || nanos - deadAtNanos < DEATH_ANIMATION_NANOS

    fun adoptTransients(other: ShadowEntity) {
        deadAtNanos = other.deadAtNanos
        hurtAtNanos = other.hurtAtNanos
        swingAtNanos = other.swingAtNanos
    }

    fun mergeMetadata(entries: List<MetadataEntry>) {
        for (entry in entries) metadata.put(entry.index, entry)
    }

    fun metadataByte(index: Int, default: Int = 0): Int {
        val entry = metadata[index] ?: return default
        return if (entry.type == MetadataEntry.BYTE) entry.byteValue() else default
    }

    fun recordPosition(nanos: Long) {
        history.positions.push(nanos, x, y, z)
    }

    fun recordRotation(nanos: Long) {
        history.rotations.push(nanos, yawDegrees.toDouble(), pitchDegrees.toDouble(), headYawDegrees.toDouble())
    }

    fun setPosition(nanos: Long, fixedX: Int, fixedY: Int, fixedZ: Int) {
        this.fixedX = fixedX
        this.fixedY = fixedY
        this.fixedZ = fixedZ
        recordPosition(nanos)
    }

    fun move(nanos: Long, deltaX: Int, deltaY: Int, deltaZ: Int) {
        fixedX += deltaX
        fixedY += deltaY
        fixedZ += deltaZ
        recordPosition(nanos)
    }

    fun setRotation(nanos: Long, yaw: Byte, pitch: Byte) {
        this.yaw = yaw
        this.pitch = pitch
        recordRotation(nanos)
    }

    fun setHeadYaw(nanos: Long, headYaw: Byte) {
        this.headYaw = headYaw
        recordRotation(nanos)
    }

    fun setPose(
        nanos: Long,
        fixedX: Int,
        fixedY: Int,
        fixedZ: Int,
        yaw: Byte,
        pitch: Byte,
        headYaw: Byte = this.headYaw
    ) {
        this.yaw = yaw
        this.pitch = pitch
        this.headYaw = headYaw
        recordRotation(nanos)
        setPosition(nanos, fixedX, fixedY, fixedZ)
    }

    fun pose(): Pose = Pose(x, y, z, yawDegrees, pitchDegrees, headYawDegrees)

    fun poseAt(nanos: Long, interpolation: Interpolation): Pose = interpolation.poseAt(history, nanos) ?: pose()

    companion object {
        const val DEATH_ANIMATION_TICKS = 20
        const val DEATH_ANIMATION_NANOS = DEATH_ANIMATION_TICKS * 50_000_000L
    }
}
