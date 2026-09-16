package gg.sona.afterimage.mc263.state

import gg.sona.afterimage.mc263.mixin.EntityDataIdsAccessor
import gg.sona.afterimage.mc263.mixin.UpdateAttributesInvoker
import gg.sona.afterimage.mc263.state.ShadowEntity.Companion.toRef
import gg.sona.afterimage.protocol.LocalScreen
import gg.sona.afterimage.protocol.LocalTarget
import net.minecraft.network.protocol.common.ClientboundPostEffectsPacket
import net.minecraft.network.protocol.game.ClientboundSetCursorItemPacket
import net.minecraft.network.protocol.game.ClientboundSetPlayerInventoryPacket
import gg.sona.afterimage.world.ItemRef
import gg.sona.afterimage.world.LocalPlayerState
import gg.sona.afterimage.world.MotionHistory
import gg.sona.afterimage.world.Pose
import gg.sona.afterimage.world.RecorderIdentity
import gg.sona.afterimage.world.camera.CameraSamples
import gg.sona.afterimage.world.interpolation.Interpolation
import net.minecraft.core.Holder
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket
import net.minecraft.network.protocol.game.ClientboundPlayerAbilitiesPacket
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket
import net.minecraft.network.protocol.game.ClientboundPlayerRotationPacket
import net.minecraft.network.protocol.game.ClientboundSetCameraPacket
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket
import net.minecraft.network.protocol.game.ClientboundSetExperiencePacket
import net.minecraft.network.protocol.game.ClientboundSetHealthPacket
import net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket
import net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket
import net.minecraft.network.protocol.game.ClientboundUpdateMobEffectPacket
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.world.entity.PositionMoveRotation
import net.minecraft.world.entity.Relative
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.GameType
import net.minecraft.world.phys.Vec3
import java.util.*

class ShadowLocalPlayer(identity: RecorderIdentity) : LocalPlayerState {
    override var entityId: Int = -1
    override var uuid: UUID? = identity.uuid
    override var name: String? = identity.name
    override var hasPosition: Boolean = false
    override var x: Double = 0.0
    override var y: Double = 0.0
    override var z: Double = 0.0
    override var yaw: Float = 0f
    override var pitch: Float = 0f
    override var onGround: Boolean = false
    var gameType: GameType = GameType.SURVIVAL
    var hardcore: Boolean = false
    var healthPacket: ClientboundSetHealthPacket? = null
    var experience: ClientboundSetExperiencePacket? = null
    override var heldSlot: Int = 0
    var abilities: ClientboundPlayerAbilitiesPacket? = null
    var camera: ClientboundSetCameraPacket? = null
    var postEffects: ClientboundPostEffectsPacket? = null
    var screen: LocalScreen = LocalScreen.CLOSED
    var target: LocalTarget? = null
    override var vehicleId: Int = -1
    var inventory: MutableList<ItemStack> = ArrayList()
    var inventoryStateId: Int = 0
    var carried: ItemStack = ItemStack.EMPTY
    val data = TreeMap<Int, SynchedEntityData.DataValue<*>>()
    val attributes = LinkedHashMap<Holder<*>, ClientboundUpdateAttributesPacket.AttributeSnapshot>()
    val effects = LinkedHashMap<Holder<*>, ShadowEffect>()
    override val history = MotionHistory()
    override val cameraFrames = CameraSamples()
    val swings = ShadowSwings()
    var lastAttackNanos: Long = Long.MIN_VALUE
    var hurtAtNanos: Long = Long.MIN_VALUE
    var deadAtNanos: Long = Long.MIN_VALUE

    override val gameMode: Int get() = gameType.id
    override val isSpectator: Boolean get() = gameType == GameType.SPECTATOR
    override val flags: Int get() = ((data[EntityDataIdsAccessor.afterimage_sharedFlags().id()]?.value() as? Byte)?.toInt() ?: 0) and 0xFF
    override val sneaking: Boolean get() = flags and FLAG_SNEAKING != 0
    override val sprinting: Boolean get() = flags and FLAG_SPRINTING != 0
    override val food: Int get() = healthPacket?.food ?: 20
    override val health: Float get() = healthPacket?.health ?: 20f
    override val held: ItemRef? get() = inventory.getOrNull(HOTBAR_START + heldSlot.coerceIn(0, 8)).toRef()

    fun itemIn(slot: EquipmentSlot): ItemStack = when (slot) {
        EquipmentSlot.MAINHAND -> inventory.getOrNull(HOTBAR_START + heldSlot.coerceIn(0, 8)) ?: ItemStack.EMPTY
        EquipmentSlot.OFFHAND -> inventory.getOrNull(OFFHAND_SLOT) ?: ItemStack.EMPTY
        EquipmentSlot.HEAD -> inventory.getOrNull(ARMOR_START) ?: ItemStack.EMPTY
        EquipmentSlot.CHEST -> inventory.getOrNull(ARMOR_START + 1) ?: ItemStack.EMPTY
        EquipmentSlot.LEGS -> inventory.getOrNull(ARMOR_START + 2) ?: ItemStack.EMPTY
        EquipmentSlot.FEET -> inventory.getOrNull(ARMOR_START + 3) ?: ItemStack.EMPTY
        else -> ItemStack.EMPTY
    }

    override fun equipment(slot: Int): ItemRef? = when (slot) {
        0 -> held
        1 -> inventory.getOrNull(ARMOR_START + 3).toRef()
        2 -> inventory.getOrNull(ARMOR_START + 2).toRef()
        3 -> inventory.getOrNull(ARMOR_START + 1).toRef()
        4 -> inventory.getOrNull(ARMOR_START).toRef()
        else -> null
    }

    override fun poseAt(nanos: Long, interpolation: Interpolation): Pose = interpolation.poseAt(history, nanos) ?: Pose(x, y, z, yaw, pitch, yaw)

    fun setPose(nanos: Long, x: Double, y: Double, z: Double, yaw: Float, pitch: Float) {
        this.x = x
        this.y = y
        this.z = z
        this.yaw = yaw
        this.pitch = pitch
        hasPosition = true
        history.positions.push(nanos, x, y, z)
        history.rotations.push(nanos, yaw.toDouble(), pitch.toDouble(), yaw.toDouble())
    }

    fun apply(packet: ClientboundPlayerPositionPacket, nanos: Long) {
        val change = packet.change()
        val relatives = packet.relatives()
        val position = change.position()
        val nx = if (Relative.X in relatives) x + position.x else position.x
        val ny = if (Relative.Y in relatives) y + position.y else position.y
        val nz = if (Relative.Z in relatives) z + position.z else position.z
        val nyaw = if (Relative.Y_ROT in relatives) yaw + change.yRot() else change.yRot()
        val npitch = if (Relative.X_ROT in relatives) pitch + change.xRot() else change.xRot()
        setPose(nanos, nx, ny, nz, nyaw, npitch)
    }

    fun apply(packet: ClientboundPlayerRotationPacket, nanos: Long) {
        val nyaw = if (packet.relativeY()) yaw + packet.yRot() else packet.yRot()
        val npitch = if (packet.relativeX()) pitch + packet.xRot() else packet.xRot()
        setPose(nanos, x, y, z, nyaw, npitch)
    }

    fun apply(packet: ClientboundContainerSetContentPacket) {
        if (packet.containerId() != 0) return
        inventory = ArrayList(packet.items())
        inventoryStateId = packet.stateId()
        carried = packet.carriedItem()
    }

    fun apply(packet: ClientboundContainerSetSlotPacket) {
        if (packet.containerId == -1) {
            carried = packet.item
            return
        }
        if (packet.containerId != 0) return
        while (inventory.size <= packet.slot) inventory.add(ItemStack.EMPTY)
        inventory[packet.slot] = packet.item
        inventoryStateId = packet.stateId
    }

    fun apply(packet: ClientboundSetPlayerInventoryPacket) {
        val slot = menuSlotOf(packet.slot())
        if (slot < 0) return
        while (inventory.size <= slot) inventory.add(ItemStack.EMPTY)
        inventory[slot] = packet.contents()
    }

    private fun menuSlotOf(inventoryIndex: Int): Int = when (inventoryIndex) {
        in 0..8 -> HOTBAR_START + inventoryIndex
        in 9..35 -> inventoryIndex
        in 36..39 -> ARMOR_START + (39 - inventoryIndex)
        40 -> OFFHAND_SLOT
        else -> -1
    }

    fun apply(packet: ClientboundSetCursorItemPacket) {
        carried = packet.contents()
    }

    fun mergeData(values: List<SynchedEntityData.DataValue<*>>) {
        for (value in values) data[value.id()] = value
    }

    fun adoptTransients(other: ShadowLocalPlayer) {
        swings.copyFrom(other.swings)
        lastAttackNanos = other.lastAttackNanos
        hurtAtNanos = other.hurtAtNanos
        deadAtNanos = other.deadAtNanos
    }

    fun resetForRespawn() {
        effects.clear()
        swings.clear()
        lastAttackNanos = Long.MIN_VALUE
        camera = null
        vehicleId = -1
        deadAtNanos = Long.MIN_VALUE
        hasPosition = false
        history.clear()
    }

    fun clear() {
        entityId = -1
        hasPosition = false
        history.clear()
        cameraFrames.clear()
        healthPacket = null
        experience = null
        heldSlot = 0
        abilities = null
        camera = null
        postEffects = null
        screen = LocalScreen.CLOSED
        target = null
        vehicleId = -1
        inventory = ArrayList()
        carried = ItemStack.EMPTY
        data.clear()
        attributes.clear()
        effects.clear()
        swings.clear()
        lastAttackNanos = Long.MIN_VALUE
        hurtAtNanos = Long.MIN_VALUE
        deadAtNanos = Long.MIN_VALUE
    }

    fun positionPacket(): ClientboundPlayerPositionPacket =
        ClientboundPlayerPositionPacket(0, PositionMoveRotation(Vec3(x, y, z), Vec3.ZERO, yaw, pitch), EnumSet.noneOf(Relative::class.java))

    fun snapshot(out: MutableList<Packet<*>>, nanos: Long) {
        abilities?.let { out += it }
        out += ClientboundSetHeldSlotPacket(heldSlot)
        if (hasPosition) out += positionPacket()
        healthPacket?.let { out += it }
        experience?.let { out += it }
        if (inventory.isNotEmpty()) out += ClientboundContainerSetContentPacket(0, inventoryStateId, ArrayList(inventory), carried)
        if (data.isNotEmpty()) out += ClientboundSetEntityDataPacket(entityId, ArrayList(data.values))
        if (attributes.isNotEmpty()) out += UpdateAttributesInvoker.afterimage_create(entityId, ArrayList(attributes.values))
        for (effect in effects.values) effect.packetAt(entityId, nanos)?.let { out += it }
        camera?.let { out += it }
        postEffects?.let { out += it }
    }

    companion object {
        const val ARMOR_START = 5
        const val HOTBAR_START = 36
        const val OFFHAND_SLOT = 45
        const val FLAG_SNEAKING = 0x02
        const val FLAG_SPRINTING = 0x08
    }
}
