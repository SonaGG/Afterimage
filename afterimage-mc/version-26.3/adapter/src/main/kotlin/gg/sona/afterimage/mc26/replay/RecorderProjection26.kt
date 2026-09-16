package gg.sona.afterimage.mc26.replay

import com.mojang.authlib.GameProfile
import com.mojang.datafixers.util.Pair
import gg.sona.afterimage.mc.common.FootstepBlock
import gg.sona.afterimage.mc.common.FootstepTracker
import gg.sona.afterimage.mc26.mixin.EntityDataIdsAccessor
import gg.sona.afterimage.mc26.mixin.EntityEventAccessor
import gg.sona.afterimage.mc26.mixin.MoveEntityAccessor
import gg.sona.afterimage.mc26.mixin.RotateHeadAccessor
import gg.sona.afterimage.mc26.mixin.RotateHeadInvoker
import gg.sona.afterimage.mc26.mixin.UpdateAttributesInvoker
import gg.sona.afterimage.mc26.state.ShadowClient26
import gg.sona.afterimage.mc26.state.ShadowPlayers26
import gg.sona.afterimage.mc26.state.ShadowSwings26
import gg.sona.afterimage.net.CapturedPacket
import gg.sona.afterimage.net.PackedPosition
import gg.sona.afterimage.net.PacketDirection
import gg.sona.afterimage.protocol.AfterimageInternal
import gg.sona.afterimage.protocol.InternalCodec
import gg.sona.afterimage.protocol.LocalBlockBreak
import gg.sona.afterimage.protocol.LocalPose
import gg.sona.afterimage.protocol.LocalHand
import gg.sona.afterimage.replay.consumer.DeliveryMode
import gg.sona.afterimage.replay.consumer.ReplayConsumer
import gg.sona.afterimage.replay.consumer.ResetReason
import io.netty.buffer.Unpooled
import net.minecraft.core.BlockPos
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.common.ClientboundCustomReportDetailsPacket
import net.minecraft.network.protocol.common.ClientboundDisconnectPacket
import net.minecraft.network.protocol.common.ClientboundKeepAlivePacket
import net.minecraft.network.protocol.common.ClientboundPingPacket
import net.minecraft.network.protocol.common.ClientboundResourcePackPopPacket
import net.minecraft.network.protocol.common.ClientboundResourcePackPushPacket
import net.minecraft.network.protocol.common.ClientboundServerLinksPacket
import net.minecraft.network.protocol.common.ClientboundShowDialogPacket
import net.minecraft.network.protocol.common.ClientboundStoreCookiePacket
import net.minecraft.network.protocol.common.ClientboundTransferPacket
import net.minecraft.network.protocol.game.*
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.tags.BlockTags
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.PositionMoveRotation
import net.minecraft.world.entity.Relative
import net.minecraft.world.entity.player.Abilities
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.component.SwingAnimation
import net.minecraft.world.level.GameType
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.Vec3
import java.util.*
import kotlin.math.floor

class RecorderProjection26(private val shadow: ShadowClient26, private val downstream: ReplayConsumer) : ReplayConsumer {
    private val pending = ArrayList<CapturedPacket>()
    private val pendingDigs = HashMap<Long, Int>()
    private val lastEquipment = EnumMap<EquipmentSlot, ItemStack>(EquipmentSlot::class.java)
    private val footsteps = FootstepTracker({ x, y, z -> probe(x, y, z) }, SoundEvents.PLAYER_SWIM.location().toString())
    var recorderEntityId: Int = -1
        private set
    var recorderSpawned: Boolean = false
        private set
    var recorderMissing: (Int) -> Boolean = { false }
    var mirrorHud: Boolean = true
    private var cameraPlaced = false
    private var dimension: String? = null
    private var nativeSwings = false

    override fun onReset(reason: ResetReason) {
        recorderSpawned = false
        cameraPlaced = false
        pending.clear()
        lastEquipment.clear()
        footsteps.reset()
        downstream.onReset(reason)
    }

    override fun onSettled(positionNanos: Long, mode: DeliveryMode) {
        val player = shadow.localPlayer
        if (shadow.consumeFreshRecorder() && recorderSpawned && recorderEntityId != -1) {
            emit(ClientboundRemoveEntitiesPacket(recorderEntityId), positionNanos, mode)
            recorderSpawned = false
            spawnRecorder(positionNanos, mode)
        }
        if (recorderSpawned && player.deadAtNanos == Long.MIN_VALUE && player.health > 0f && recorderMissing(recorderEntityId)) {
            recorderSpawned = false
            spawnRecorder(positionNanos, mode)
        }
        downstream.onSettled(positionNanos, mode)
    }

    override fun onPacket(packet: CapturedPacket, mode: DeliveryMode) {
        if (packet.direction == PacketDirection.SERVERBOUND) outbound(packet, mode) else inbound(packet, mode)
    }

    private fun decoded(packet: CapturedPacket): Packet<*>? {
        val cached = shadow.lastDecoded
        if (cached != null && cached.first === packet) return cached.second
        return shadow.codecs.decode(packet)
    }

    private fun inbound(packet: CapturedPacket, mode: DeliveryMode) {
        if (!shadow.codecs.ready) {
            forward(packet, mode)
            return
        }
        val decoded = decoded(packet) ?: return forward(packet, mode)
        val nanos = packet.timestampNanos
        when (decoded) {
            is ClientboundGameEventPacket -> if (decoded.event != ClientboundGameEventPacket.CHANGE_GAME_MODE) forward(packet, mode)
            is ClientboundLoginPacket -> {
                recorderEntityId = decoded.playerId()
                dimension = decoded.commonPlayerSpawnInfo().dimension().identifier().toString()
                recorderSpawned = false
                cameraPlaced = false
                pending.clear()
                lastEquipment.clear()
                footsteps.reset()
                val info = cameraSpawnInfo(decoded.commonPlayerSpawnInfo())
                emit(
                    ClientboundLoginPacket(
                        CAMERA_ENTITY_ID, decoded.hardcore(), decoded.levels(), decoded.maxPlayers(), decoded.chunkRadius(), decoded.simulationDistance(),
                        false, false, decoded.doLimitedCrafting(), info, decoded.onlineMode(), decoded.enforcesSecureChat(),
                    ), nanos, mode,
                )
                emit(ClientboundPlayerAbilitiesPacket(cameraAbilities()), nanos, mode)
            }

            is ClientboundPlayerInfoUpdatePacket -> {
                forward(packet, mode)
                val recorder = recorderUuid()
                if (decoded.entries().any { it.profileId() == recorder }) syncCameraInfo(nanos, mode)
            }

            is ClientboundBlockUpdatePacket -> {
                forward(packet, mode)
                if (pendingDigs.isNotEmpty() && decoded.blockState.isAir) {
                    val state = pendingDigs.remove(decoded.pos.asLong())
                    if (state != null && state != 0) emit(ClientboundLevelEventPacket(BLOCK_BREAK_EFFECT, decoded.pos, state, false), nanos, mode)
                }
            }

            is ClientboundRespawnPacket -> {
                val key = decoded.commonPlayerSpawnInfo().dimension().identifier().toString()
                if (key != dimension) {
                    pending.clear()
                    footsteps.reset()
                }
                recorderSpawned = false
                dimension = key
                cameraPlaced = false
                emit(ClientboundRespawnPacket(cameraSpawnInfo(decoded.commonPlayerSpawnInfo()), decoded.dataToKeep()), nanos, mode)
                emit(ClientboundPlayerAbilitiesPacket(cameraAbilities()), nanos, mode)
            }

            is ClientboundPlayerPositionPacket, is ClientboundPlayerRotationPacket -> {
                val player = shadow.localPlayer
                if (!recorderSpawned) spawnRecorder(nanos, mode) else teleportRecorder(nanos, mode)
                footsteps.reposition(player.x, player.y, player.z)
                if (!cameraPlaced && player.hasPosition) {
                    emit(cameraPosition(), nanos, mode)
                    cameraPlaced = true
                }
            }

            is ClientboundSetHeldSlotPacket -> {
                if (mirrorHud) forward(packet, mode)
                syncEquipment(nanos, mode)
            }

            is ClientboundContainerSetContentPacket -> {
                if (mirrorHud) {
                    if (decoded.containerId() == 0) forward(packet, mode)
                    else shadow.window?.takeIf { it.id == decoded.containerId() }?.let {
                        emit(ClientboundContainerSetContentPacket(0, shadow.localPlayer.inventoryStateId, ArrayList(shadow.localPlayer.inventory), shadow.localPlayer.carried), nanos, mode)
                    }
                }
                syncEquipment(nanos, mode)
            }

            is ClientboundContainerSetSlotPacket -> {
                if (mirrorHud) {
                    if (decoded.containerId == 0 || decoded.containerId == -1) forward(packet, mode)
                    else shadow.window?.takeIf { it.id == decoded.containerId }?.let { window ->
                        val playerSlot = decoded.slot - (window.size - PLAYER_SLOTS_IN_CONTAINER)
                        if (playerSlot in 0 until PLAYER_SLOTS_IN_CONTAINER) emit(ClientboundContainerSetSlotPacket(0, decoded.stateId, MAIN_INVENTORY_START + playerSlot, decoded.item), nanos, mode)
                    }
                }
                syncEquipment(nanos, mode)
            }

            is ClientboundSetPlayerInventoryPacket, is ClientboundSetCursorItemPacket -> {
                if (mirrorHud) forward(packet, mode)
                syncEquipment(nanos, mode)
            }

            is ClientboundSetHealthPacket -> if (mirrorHud) {
                emit(ClientboundSetHealthPacket(if (decoded.health <= 0f) MIN_MIRRORED_HEALTH else decoded.health, decoded.food, decoded.saturation), nanos, mode)
            }

            is ClientboundSetExperiencePacket -> if (mirrorHud) forward(packet, mode)
            is ClientboundRemoveEntitiesPacket -> {
                val ids = decoded.entityIds()
                if (ids.none { it == recorderEntityId }) forward(packet, mode)
                else emit(ClientboundRemoveEntitiesPacket(*ids.filter { it != recorderEntityId }.toIntArray()), nanos, mode)
            }

            is ClientboundPlayerAbilitiesPacket, is ClientboundOpenScreenPacket, is ClientboundContainerClosePacket, is ClientboundContainerSetDataPacket,
            is ClientboundMountScreenOpenPacket, is ClientboundMerchantOffersPacket,
            is ClientboundKeepAlivePacket, is ClientboundPingPacket, is ClientboundDisconnectPacket, is ClientboundResourcePackPushPacket, is ClientboundResourcePackPopPacket,
            is ClientboundSetCameraPacket, is ClientboundAwardStatsPacket, is ClientboundCommandSuggestionsPacket, is ClientboundOpenSignEditorPacket, is ClientboundOpenBookPacket,
            is ClientboundShowDialogPacket, is ClientboundTransferPacket, is ClientboundStoreCookiePacket, is ClientboundCustomReportDetailsPacket, is ClientboundServerLinksPacket,
            is ClientboundPlayerLookAtPacket, is ClientboundPlayerCombatKillPacket,
                -> Unit

            else -> {
                val target = targetOf(decoded)
                if (target == recorderEntityId && !recorderSpawned) {
                    if (pending.size < MAX_PENDING_PACKETS) pending += packet
                } else {
                    forward(packet, mode)
                    if (target == recorderEntityId && mirrorHud) mirrorToCamera(decoded, nanos, mode)
                }
            }
        }
    }

    private fun cameraSpawnInfo(info: CommonPlayerSpawnInfo): CommonPlayerSpawnInfo = CommonPlayerSpawnInfo(
        info.dimensionType(), info.dimension(), info.seed(), CAMERA_GAME_MODE, Optional.empty(), info.isDebug, info.isFlat, Optional.empty(), info.portalCooldown(), info.seaLevel(),
    )

    private fun cameraAbilities(): Abilities = Abilities().also {
        it.invulnerable = true
        it.flying = true
        it.mayfly = true
        it.mayBuild = false
        it.flyingSpeed = 0.05f
        it.walkingSpeed = 0.1f
    }

    private fun cameraPosition(): ClientboundPlayerPositionPacket {
        val player = shadow.localPlayer
        return ClientboundPlayerPositionPacket(0, PositionMoveRotation(Vec3(player.x, player.y, player.z), Vec3.ZERO, player.yaw, player.pitch), EnumSet.noneOf(Relative::class.java))
    }

    private fun targetOf(packet: Packet<*>): Int = when (packet) {
        is ClientboundSetEntityDataPacket -> packet.id()
        is ClientboundSetEquipmentPacket -> packet.entity
        is ClientboundUpdateAttributesPacket -> packet.entityId
        is ClientboundUpdateMobEffectPacket -> packet.entityId
        is ClientboundRemoveMobEffectPacket -> packet.entityId()
        is ClientboundEntityEventPacket -> (packet as EntityEventAccessor).afterimage_entityId()
        is ClientboundDamageEventPacket -> packet.entityId()
        is ClientboundHurtAnimationPacket -> packet.id()
        is ClientboundSwingAnimationPacket -> packet.entityId()
        is ClientboundAnimatePacket -> packet.id
        is ClientboundTeleportEntityPacket -> packet.id()
        is ClientboundEntityPositionSyncPacket -> packet.id()
        is ClientboundMoveEntityPacket -> (packet as MoveEntityAccessor).afterimage_entityId()
        is ClientboundRotateHeadPacket -> (packet as RotateHeadAccessor).afterimage_entityId()
        is ClientboundSetEntityMotionPacket -> packet.id()
        is ClientboundSetPassengersPacket -> packet.vehicle
        is ClientboundSetEntityLinkPacket -> packet.sourceId
        is ClientboundTakeItemEntityPacket -> packet.playerId
        is ClientboundSoundEntityPacket -> packet.id
        else -> Int.MIN_VALUE
    }

    private fun footstep(onGround: Boolean, nanos: Long, mode: DeliveryMode) {
        val player = shadow.localPlayer
        val step = footsteps.move(player.x, player.y, player.z, onGround, player.sneaking, player.vehicleId != -1, nanos) ?: return
        if (mode != DeliveryMode.LIVE) return
        val registries = shadow.codecs.registries ?: return
        val event = net.minecraft.core.registries.BuiltInRegistries.SOUND_EVENT.get(net.minecraft.resources.Identifier.parse(step.sound)).orElse(null) ?: return
        emit(ClientboundSoundPacket(event, SoundSource.PLAYERS, player.x, player.y, player.z, step.volume, step.pitch, nanos), nanos, mode)
    }

    private fun probe(x: Int, y: Int, z: Int): FootstepBlock {
        val state = Block.stateById(shadow.level.blockState(x, y, z))
        val fluid = state.fluidState
        val sound = state.soundType
        return FootstepBlock(
            state.isAir,
            !fluid.isEmpty,
            fluid.`is`(net.minecraft.tags.FluidTags.WATER),
            state.`is`(BlockTags.CLIMBABLE),
            state.`is`(BlockTags.FENCES) || state.`is`(BlockTags.WALLS) || state.`is`(BlockTags.FENCE_GATES),
            state.`is`(Blocks.SNOW),
            if (state.isAir) null else sound.stepSound.location().toString(),
            sound.volume,
            sound.pitch,
        )
    }

    private fun mirrorToCamera(packet: Packet<*>, nanos: Long, mode: DeliveryMode) {
        when (packet) {
            is ClientboundUpdateMobEffectPacket -> {
                val remaining = shadow.localPlayer.effects[packet.effect]?.packetAt(CAMERA_ENTITY_ID, nanos) ?: return
                emit(remaining, nanos, mode)
            }

            is ClientboundRemoveMobEffectPacket -> emit(ClientboundRemoveMobEffectPacket(CAMERA_ENTITY_ID, packet.effect()), nanos, mode)
            is ClientboundSwingAnimationPacket -> emit(ClientboundSwingAnimationPacket(CAMERA_ENTITY_ID, packet.hand(), packet.animation()), nanos, mode)
            is ClientboundSetEntityDataPacket -> {
                val entries = cameraData(packet.packedItems())
                if (entries.isNotEmpty()) emit(ClientboundSetEntityDataPacket(CAMERA_ENTITY_ID, entries), nanos, mode)
            }

            else -> Unit
        }
    }

    private fun cameraData(values: List<SynchedEntityData.DataValue<*>>): List<SynchedEntityData.DataValue<*>> {
        val flagsId = EntityDataIdsAccessor.afterimage_sharedFlags().id()
        val airId = EntityDataIdsAccessor.afterimage_airSupply().id()
        return values.mapNotNull { value ->
            when (value.id()) {
                flagsId -> {
                    val flags = (value.value() as? Byte)?.toInt() ?: return@mapNotNull null
                    SynchedEntityData.DataValue.create(EntityDataIdsAccessor.afterimage_sharedFlags(), (flags and CAMERA_FLAG_MASK).toByte())
                }

                airId -> value
                else -> null
            }
        }
    }

    private fun outbound(packet: CapturedPacket, mode: DeliveryMode) {
        val nanos = packet.timestampNanos
        if (AfterimageInternal.isInternal(packet.packetId)) {
            when (val internal = InternalCodec.decode(packet)) {
                is LocalPose -> if (recorderSpawned) teleportRecorder(nanos, mode)
                is LocalHand -> if (internal.isSwing) {
                    nativeSwings = true
                    swing(ShadowSwings26.handOf(internal), ShadowSwings26.animationOf(internal), nanos, mode)
                } else if (mirrorHud) forward(packet, mode)

                is LocalBlockBreak -> if (recorderSpawned) {
                    emit(ClientboundBlockDestructionPacket(recorderEntityId, blockPos(internal.position), internal.stage), nanos, mode)
                    forward(packet, mode)
                }

                else -> forward(packet, mode)
            }
            return
        }
        if (!shadow.codecs.ready) return
        when (val decoded = decoded(packet)) {
            is ServerboundMovePlayerPacket -> if (recorderSpawned) {
                teleportRecorder(nanos, mode)
                if (decoded.hasPosition()) footstep(decoded.isOnGround, nanos, mode)
            }

            is ServerboundAttackPacket, is ServerboundPunchPacket -> swing(InteractionHand.MAIN_HAND, nanos, mode)
            is ServerboundUseItemPacket -> swing(decoded.hand, nanos, mode, attack = false)
            is ServerboundUseItemOnPacket -> swing(decoded.hand, nanos, mode, attack = false)
            is ServerboundInteractPacket -> swing(decoded.hand(), nanos, mode, attack = false)
            is ServerboundSetCarriedItemPacket -> {
                if (mirrorHud) emit(ClientboundSetHeldSlotPacket(decoded.slot), nanos, mode)
                syncEquipment(nanos, mode)
            }

            is ServerboundPlayerActionPacket -> if (recorderSpawned) {
                val pos = decoded.pos
                val current = shadow.level.blockState(pos.x, pos.y, pos.z)
                when (decoded.action) {
                    ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK -> {
                        swing(InteractionHand.MAIN_HAND, nanos, mode)
                        if (pendingDigs.size > MAX_PENDING_DIGS) pendingDigs.clear()
                        if (current != 0) pendingDigs[pos.asLong()] = current
                        if (shadow.localPlayer.gameType == GameType.CREATIVE && current != 0) emit(ClientboundLevelEventPacket(BLOCK_BREAK_EFFECT, pos, current, false), nanos, mode)
                    }

                    ServerboundPlayerActionPacket.Action.STAB -> swing(InteractionHand.MAIN_HAND, nanos, mode)
                    ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK -> pendingDigs.remove(pos.asLong())
                    ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK -> {
                        val state = pendingDigs.remove(pos.asLong()) ?: current
                        if (state != 0) emit(ClientboundLevelEventPacket(BLOCK_BREAK_EFFECT, pos, state, false), nanos, mode)
                    }

                    else -> Unit
                }
            }

            else -> Unit
        }
    }

    private fun swing(hand: InteractionHand, nanos: Long, mode: DeliveryMode, attack: Boolean = true) {
        if (nativeSwings) return
        val held = shadow.localPlayer.itemIn(if (hand == InteractionHand.MAIN_HAND) EquipmentSlot.MAINHAND else EquipmentSlot.OFFHAND)
        swing(hand, if (attack) held.attackAnimation else held.interactAnimation, nanos, mode)
    }

    private fun swing(hand: InteractionHand, animation: SwingAnimation, nanos: Long, mode: DeliveryMode) {
        if (!recorderSpawned) return
        emit(ClientboundSwingAnimationPacket(recorderEntityId, hand, animation), nanos, mode)
        if (mirrorHud) emit(ClientboundSwingAnimationPacket(CAMERA_ENTITY_ID, hand, animation), nanos, mode)
    }

    private fun blockPos(packed: Long): BlockPos = BlockPos(PackedPosition.x(packed), PackedPosition.y(packed), PackedPosition.z(packed))

    private fun recorderUuid(): UUID = shadow.localPlayer.uuid ?: UUID.nameUUIDFromBytes("OfflinePlayer:${shadow.localPlayer.name ?: "Afterimage"}".toByteArray())

    private fun syncCameraInfo(nanos: Long, mode: DeliveryMode) {
        val recorder = recorderUuid()
        val source = shadow.players.profile(recorder)?.entry
        val sourceProfile = source?.profile()
        val name = sourceProfile?.name() ?: shadow.localPlayer.name ?: "Afterimage"
        val profile = if (sourceProfile != null) GameProfile(cameraUuid(recorder), name, sourceProfile.properties()) else GameProfile(cameraUuid(recorder), name)
        val entry = ClientboundPlayerInfoUpdatePacket.Entry(profile.id(), profile, false, 0, CAMERA_GAME_MODE, null, source?.showHat() ?: true, 0, null)
        emit(ShadowPlayers26.addPacket(listOf(entry)), nanos, mode)
    }

    private fun spawnRecorder(nanos: Long, mode: DeliveryMode) {
        val player = shadow.localPlayer
        if (!player.hasPosition || recorderEntityId == -1) return
        val uuid = player.uuid ?: UUID.nameUUIDFromBytes("OfflinePlayer:${player.name ?: "Afterimage"}".toByteArray())
        syncCameraInfo(nanos, mode)
        val listed = shadow.players.entries.containsKey(uuid)
        val entry = shadow.players.profile(uuid)?.entry ?: ClientboundPlayerInfoUpdatePacket.Entry(
            uuid, GameProfile(uuid, player.name ?: "Afterimage"), false, 0, GameType.byId(player.gameMode) ?: GameType.SURVIVAL, null, true, 0, null,
        )
        if (!listed) emit(ShadowPlayers26.addPacket(listOf(entry)), nanos, mode)
        emit(
            ClientboundAddEntityPacket(recorderEntityId, uuid, player.x, player.y, player.z, player.pitch, player.yaw, EntityTypes.PLAYER, 0, Vec3.ZERO, player.yaw.toDouble()),
            nanos, mode,
        )
        emit(rotateHead(recorderEntityId, player.yaw), nanos, mode)
        recorderSpawned = true
        if (player.data.isNotEmpty()) emit(ClientboundSetEntityDataPacket(recorderEntityId, ArrayList(player.data.values)), nanos, mode)
        for (queued in pending) forward(queued, mode)
        pending.clear()
        lastEquipment.clear()
        syncEquipment(nanos, mode)
        if (player.attributes.isNotEmpty()) emit(UpdateAttributesInvoker.afterimage_create(recorderEntityId, ArrayList(player.attributes.values)), nanos, mode)
        for (effect in player.effects.values) {
            effect.packetAt(recorderEntityId, nanos)?.let { emit(it, nanos, mode) }
            if (mirrorHud) effect.packetAt(CAMERA_ENTITY_ID, nanos)?.let { emit(it, nanos, mode) }
        }
        if (mirrorHud && player.data.isNotEmpty()) {
            val entries = cameraData(ArrayList(player.data.values))
            if (entries.isNotEmpty()) emit(ClientboundSetEntityDataPacket(CAMERA_ENTITY_ID, entries), nanos, mode)
        }
        if (!listed) emit(ClientboundPlayerInfoRemovePacket(listOf(uuid)), nanos, mode)
    }

    private fun teleportRecorder(nanos: Long, mode: DeliveryMode) {
        val player = shadow.localPlayer
        emit(
            ClientboundTeleportEntityPacket(
                recorderEntityId,
                PositionMoveRotation(Vec3(player.x, player.y, player.z), Vec3.ZERO, player.yaw, player.pitch),
                EnumSet.noneOf(Relative::class.java),
                player.onGround,
            ), nanos, mode,
        )
        emit(rotateHead(recorderEntityId, player.yaw), nanos, mode)
    }

    private fun rotateHead(id: Int, yaw: Float): Packet<*> {
        val buffer = FriendlyByteBuf(Unpooled.buffer(8))
        try {
            buffer.writeVarInt(id)
            buffer.writeByte(floor(yaw * 256.0 / 360.0).toInt())
            return RotateHeadInvoker.afterimage_read(buffer)
        } finally {
            buffer.release()
        }
    }

    private fun syncEquipment(nanos: Long, mode: DeliveryMode) {
        if (!recorderSpawned) return
        val player = shadow.localPlayer
        val changed = ArrayList<Pair<EquipmentSlot, ItemStack>>()
        for (slot in EquipmentSlot.VALUES) {
            val item = player.itemIn(slot)
            val previous = lastEquipment[slot]
            if (previous != null && ItemStack.matches(previous, item)) continue
            if (previous == null && item.isEmpty) {
                lastEquipment[slot] = item
                continue
            }
            lastEquipment[slot] = item
            changed += Pair.of(slot, item)
        }
        if (changed.isNotEmpty()) emit(ClientboundSetEquipmentPacket(recorderEntityId, changed), nanos, mode)
    }

    private fun emit(packet: Packet<*>, nanos: Long, mode: DeliveryMode) {
        val encoded = shadow.codecs.encode(packet, nanos) ?: return
        forward(encoded, mode)
    }

    private fun forward(packet: CapturedPacket, mode: DeliveryMode) = downstream.onPacket(packet, mode)

    companion object {
        const val CAMERA_ENTITY_ID = -0x52435354
        val CAMERA_GAME_MODE: GameType = GameType.ADVENTURE
        const val MAX_PENDING_PACKETS = 512
        const val MAX_PENDING_DIGS = 64
        const val BLOCK_BREAK_EFFECT = 2001
        const val MIN_MIRRORED_HEALTH = 0.01f
        const val PLAYER_SLOTS_IN_CONTAINER = 36
        const val MAIN_INVENTORY_START = 9
        const val CAMERA_FLAG_MASK = 0x01 or 0x20

        fun isCamera(entity: Entity): Boolean = entity.id == CAMERA_ENTITY_ID

        fun cameraUuid(recorder: UUID): UUID = UUID.nameUUIDFromBytes("AfterimageCamera:$recorder".toByteArray())
    }
}
