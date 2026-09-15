package gg.sona.afterimage.protocol47.shadow

import gg.sona.afterimage.world.*
import gg.sona.afterimage.core.collect.IntObjectMap
import gg.sona.afterimage.protocol.*
import gg.sona.afterimage.protocol47.diff.StateDiff
import gg.sona.afterimage.protocol47.toRef
import gg.sona.afterimage.world.camera.CameraSamples
import gg.sona.afterimage.world.interpolation.Interpolation
import java.util.*

class ShadowLocalPlayer : LocalPlayerState {
    override var entityId: Int = -1
    override var uuid: UUID? = null
    override var name: String? = null
    override var gameMode: Int = 0
    override var x: Double = 0.0
    override var y: Double = 0.0
    override var z: Double = 0.0
    override var yaw: Float = 0f
    override var pitch: Float = 0f
    override var onGround: Boolean = false
    override var hasPosition: Boolean = false
    override var health: Float = 20f
    override var food: Int = 20
    var saturation: Float = 5f
    var xpBar: Float = 0f
    var xpLevel: Int = 0
    var xpTotal: Int = 0
    override var heldSlot: Int = 0
    var abilityFlags: Int = 0
    var flyingSpeed: Float = 0.05f
    var walkingSpeed: Float = 0.1f
    override var sneaking: Boolean = false
    override var sprinting: Boolean = false
    override var vehicleId: Int = -1
    var cameraEntityId: Int = -1
    var openWindowId: Int = 0
    var openWindowSize: Int = 0
    var cursorItem: ItemStack = ItemStack.EMPTY
    var window: ShadowWindow? = null
    var screen: LocalScreen = LocalScreen.CLOSED
    var target: LocalTarget? = null
    var reducedDebugInfo: Boolean = false
    var lastSwingNanos: Long = Long.MIN_VALUE
    var hurtAtNanos: Long = Long.MIN_VALUE
    var deadAtNanos: Long = Long.MIN_VALUE
    var heldChangedAtNanos: Long = Long.MIN_VALUE
        private set
    var heldBefore: ItemStack = ItemStack.EMPTY
        private set
    private var heldSeen: ItemStack? = null

    val inventory = Array(INVENTORY_SIZE) { ItemStack.EMPTY }
    val metadata = IntObjectMap<MetadataEntry>()
    val effects = IntObjectMap<ShadowEffect>()
    val attributes = LinkedHashMap<String, EntityAttribute>()
    override val history = MotionHistory()
    override val cameraFrames = CameraSamples()

    val heldItem: ItemStack get() = inventory[HOTBAR_START + heldSlot.coerceIn(0, 8)]

    override val held: ItemRef? get() = heldItem.toRef()

    override val isSpectator: Boolean get() = gameMode == SPECTATOR

    override val flags: Int get() = flagsByte()

    override fun equipment(slot: Int): ItemRef? = equipmentItem(slot).toRef()

    fun equipmentItem(slot: Int): ItemStack = when (slot) {
        0 -> heldItem
        1 -> inventory[ARMOR_START + 3]
        2 -> inventory[ARMOR_START + 2]
        3 -> inventory[ARMOR_START + 1]
        4 -> inventory[ARMOR_START]
        else -> ItemStack.EMPTY
    }

    fun setPosition(nanos: Long, x: Double, y: Double, z: Double) {
        this.x = x
        this.y = y
        this.z = z
        hasPosition = true
        recordPosition(nanos)
    }

    fun setRotation(nanos: Long, yaw: Float, pitch: Float) {
        this.yaw = yaw
        this.pitch = pitch
        recordRotation(nanos)
    }

    fun setPose(nanos: Long, x: Double, y: Double, z: Double, yaw: Float, pitch: Float) {
        this.x = x
        this.y = y
        this.z = z
        this.yaw = yaw
        this.pitch = pitch
        hasPosition = true
        recordRotation(nanos)
        recordPosition(nanos)
    }

    fun recordPosition(nanos: Long) {
        history.positions.push(nanos, x, y, z)
    }

    fun recordRotation(nanos: Long) {
        history.rotations.push(nanos, yaw.toDouble(), pitch.toDouble(), yaw.toDouble())
    }

    fun noteHeldItem(nanos: Long) {
        val held = heldItem
        val seen = heldSeen
        heldSeen = held
        if (seen == null || StateDiff.sameItem(seen, held)) return
        heldBefore = seen
        heldChangedAtNanos = nanos
    }

    fun adoptTransients(other: ShadowLocalPlayer) {
        lastSwingNanos = other.lastSwingNanos
        hurtAtNanos = other.hurtAtNanos
        deadAtNanos = other.deadAtNanos
        heldChangedAtNanos = other.heldChangedAtNanos
        heldBefore = other.heldBefore
        heldSeen = other.heldSeen
    }

    fun pose(): Pose = Pose(x, y, z, yaw, pitch, yaw)

    override fun poseAt(nanos: Long, interpolation: Interpolation): Pose = interpolation.poseAt(history, nanos) ?: pose()

    fun flagsByte(): Int {
        val base = metadata[0]?.let { if (it.type == MetadataEntry.BYTE) it.byteValue() else 0 } ?: 0
        var flags = base and (FLAG_SNEAKING or FLAG_SPRINTING).inv()
        if (sneaking) flags = flags or FLAG_SNEAKING
        if (sprinting) flags = flags or FLAG_SPRINTING
        return flags and 0xFF
    }

    fun entityMetadata(): List<MetadataEntry> {
        val entries = ArrayList<MetadataEntry>()
        entries += MetadataEntry.ofByte(0, flagsByte())
        var hasSkin = false
        metadata.forEach { index, entry ->
            if (index == 0) return@forEach
            if (index == SKIN_FLAGS_INDEX) hasSkin = true
            entries += entry
        }
        if (!hasSkin) entries += MetadataEntry.ofByte(SKIN_FLAGS_INDEX, 0x7F)
        return entries
    }

    fun resetForRespawn() {
        sneaking = false
        sprinting = false
        vehicleId = -1
        cameraEntityId = -1
        deadAtNanos = Long.MIN_VALUE
        effects.clear()
    }

    fun clear() {
        entityId = -1
        hasPosition = false
        history.clear()
        cameraFrames.clear()
        inventory.fill(ItemStack.EMPTY)
        cursorItem = ItemStack.EMPTY
        attributes.clear()
        openWindowId = 0
        window = null
        screen = LocalScreen.CLOSED
        target = null
        xpBar = 0f
        xpLevel = 0
        xpTotal = 0
        heldSlot = 0
        health = 20f
        food = 20
        saturation = 5f
        metadata.clear()
        lastSwingNanos = Long.MIN_VALUE
        hurtAtNanos = Long.MIN_VALUE
        heldChangedAtNanos = Long.MIN_VALUE
        heldBefore = ItemStack.EMPTY
        heldSeen = null
        resetForRespawn()
    }

    companion object {
        const val INVENTORY_SIZE = 45
        const val SPECTATOR = 3
        const val ARMOR_START = 5
        const val HOTBAR_START = 36
        const val FLAG_SNEAKING = 0x02
        const val FLAG_SPRINTING = 0x08
        const val SKIN_FLAGS_INDEX = 10
    }
}
