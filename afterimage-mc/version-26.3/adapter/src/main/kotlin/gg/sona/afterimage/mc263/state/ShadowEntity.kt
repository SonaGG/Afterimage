package gg.sona.afterimage.mc263.state

import com.mojang.datafixers.util.Pair
import gg.sona.afterimage.mc263.mixin.EntityDataIdsAccessor
import gg.sona.afterimage.mc263.mixin.LivingEntityDataIdsAccessor
import gg.sona.afterimage.mc263.mixin.UpdateAttributesInvoker
import gg.sona.afterimage.world.EntityKind
import gg.sona.afterimage.world.EntityState
import gg.sona.afterimage.world.ItemRef
import gg.sona.afterimage.world.MotionHistory
import gg.sona.afterimage.world.Pose
import gg.sona.afterimage.world.interpolation.Interpolation
import net.minecraft.core.Holder
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket
import net.minecraft.network.protocol.game.ClientboundProjectilePowerPacket
import net.minecraft.network.protocol.game.ClientboundSetEntityLinkPacket
import net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket
import net.minecraft.network.protocol.game.ClientboundUpdateMobEffectPacket
import net.minecraft.network.protocol.game.VecDeltaCodec
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.projectile.Projectile
import net.minecraft.world.item.ItemStack
import net.minecraft.world.phys.Vec3
import java.util.*

class ShadowEntity(val add: ClientboundAddEntityPacket, override val spawnedAtNanos: Long, val spawnHash: Long) : EntityState {
    override val id: Int get() = add.id
    override val uuid: UUID? get() = add.uuid
    val entityType: EntityType<*> get() = add.type
    override val type: Int get() = BuiltInRegistries.ENTITY_TYPE.getId(entityType)
    override val kind: EntityKind = kindOf(entityType)
    override val isProjectile: Boolean get() = Projectile::class.java.isAssignableFrom(entityType.baseClass)
    val living: Boolean get() = kind == EntityKind.PLAYER || kind == EntityKind.MOB

    val positionCodec = VecDeltaCodec().also { it.setBase(Vec3(add.x, add.y, add.z)) }
    override var x: Double = add.x
    override var y: Double = add.y
    override var z: Double = add.z
    override var yawDegrees: Float = add.yRot
    override var pitchDegrees: Float = add.xRot
    override var headYawDegrees: Float = add.yHeadRot
    override var onGround: Boolean = false
    override var dead: Boolean = false
    var deadAtNanos: Long = Long.MIN_VALUE
    var hurtAtNanos: Long = Long.MIN_VALUE
    val swings = ShadowSwings()
    var motion: ClientboundSetEntityMotionPacket? = null
    var passengers: ClientboundSetPassengersPacket? = null
    var link: ClientboundSetEntityLinkPacket? = null
    var projectilePower: ClientboundProjectilePowerPacket? = null
    override var vehicleId: Int = -1
    val data = TreeMap<Int, SynchedEntityData.DataValue<*>>()
    val equipment = EnumMap<EquipmentSlot, ItemStack>(EquipmentSlot::class.java)
    val attributes = LinkedHashMap<Holder<*>, ClientboundUpdateAttributesPacket.AttributeSnapshot>()
    val effects = LinkedHashMap<Holder<*>, ShadowEffect>()
    override val history = MotionHistory()

    override val health: Float
        get() {
            val value = data[LivingEntityDataIdsAccessor.afterimage_health().id()]?.value() ?: return Float.NaN
            return (value as? Float) ?: Float.NaN
        }

    override val flags: Int
        get() = ((data[EntityDataIdsAccessor.afterimage_sharedFlags().id()]?.value() as? Byte)?.toInt() ?: 0) and 0xFF

    override fun equipment(slot: Int): ItemRef? = equipment[slotFor(slot)]?.toRef()

    override fun pose(): Pose = Pose(x, y, z, yawDegrees, pitchDegrees, headYawDegrees)

    override fun poseAt(nanos: Long, interpolation: Interpolation): Pose = interpolation.poseAt(history, nanos) ?: pose()

    override fun visibleAt(nanos: Long): Boolean = !dead || nanos - deadAtNanos < DEATH_ANIMATION_NANOS

    fun setPosition(nanos: Long, x: Double, y: Double, z: Double) {
        this.x = x
        this.y = y
        this.z = z
        positionCodec.setBase(Vec3(x, y, z))
        history.positions.push(nanos, x, y, z)
    }

    fun setRotation(nanos: Long, yaw: Float, pitch: Float) {
        yawDegrees = yaw
        pitchDegrees = pitch
        history.rotations.push(nanos, yaw.toDouble(), pitch.toDouble(), headYawDegrees.toDouble())
    }

    fun setHeadYaw(nanos: Long, headYaw: Float) {
        headYawDegrees = headYaw
        history.rotations.push(nanos, yawDegrees.toDouble(), pitchDegrees.toDouble(), headYaw.toDouble())
    }

    fun mergeData(values: List<SynchedEntityData.DataValue<*>>) {
        for (value in values) data[value.id()] = value
    }

    fun adoptTransients(other: ShadowEntity) {
        deadAtNanos = other.deadAtNanos
        hurtAtNanos = other.hurtAtNanos
        swings.copyFrom(other.swings)
    }

    fun spawnPackets(out: MutableList<Packet<*>>, nanos: Long) {
        out += ClientboundAddEntityPacket(
            id, add.uuid, x, y, z, pitchDegrees, yawDegrees, entityType, add.data,
            motion?.movement() ?: add.movement, headYawDegrees.toDouble(),
        )
        if (data.isNotEmpty()) out += ClientboundSetEntityDataPacket(id, ArrayList(data.values))
        if (equipment.isNotEmpty()) out += ClientboundSetEquipmentPacket(id, equipment.entries.map { Pair.of(it.key, it.value) })
        if (attributes.isNotEmpty()) out += UpdateAttributesInvoker.afterimage_create(id, ArrayList(attributes.values))
        for (effect in effects.values) effect.packetAt(id, nanos)?.let { out += it }
    }

    fun attachmentPackets(out: MutableList<Packet<*>>) {
        passengers?.let { out += it }
        link?.let { out += it }
        projectilePower?.let { out += it }
    }

    companion object {
        const val DEATH_ANIMATION_NANOS = 20 * 50_000_000L

        fun kindOf(type: EntityType<*>): EntityKind = when {
            type === EntityTypes.PLAYER -> EntityKind.PLAYER
            type === EntityTypes.PAINTING -> EntityKind.PAINTING
            type === EntityTypes.EXPERIENCE_ORB -> EntityKind.EXPERIENCE_ORB
            type === EntityTypes.LIGHTNING_BOLT -> EntityKind.GLOBAL
            LivingEntity::class.java.isAssignableFrom(type.baseClass) -> EntityKind.MOB
            else -> EntityKind.OBJECT
        }

        fun slotOrder(): List<EquipmentSlot> = EquipmentSlot.VALUES

        fun slotFor(slot: Int): EquipmentSlot = when (slot) {
            0 -> EquipmentSlot.MAINHAND
            1 -> EquipmentSlot.FEET
            2 -> EquipmentSlot.LEGS
            3 -> EquipmentSlot.CHEST
            4 -> EquipmentSlot.HEAD
            else -> EquipmentSlot.OFFHAND
        }

        fun ItemStack?.toRef(): ItemRef? {
            if (this == null || isEmpty) return null
            val id = BuiltInRegistries.ITEM.getId(item)
            return ItemRef(id, count, BuiltInRegistries.ITEM.getKey(item).path.replace('_', ' '))
        }
    }
}
