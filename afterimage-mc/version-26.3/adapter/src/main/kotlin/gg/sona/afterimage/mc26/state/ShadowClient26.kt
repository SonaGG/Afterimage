package gg.sona.afterimage.mc26.state

import gg.sona.afterimage.core.collect.IntObjectMap
import gg.sona.afterimage.mc26.mixin.EntityEventAccessor
import gg.sona.afterimage.mc26.mixin.MoveEntityAccessor
import gg.sona.afterimage.mc26.mixin.RotateHeadAccessor
import gg.sona.afterimage.mc26.net.PacketIds26
import gg.sona.afterimage.mc26.protocol.Protocol26
import gg.sona.afterimage.net.CapturedPacket
import gg.sona.afterimage.net.PacketDirection
import gg.sona.afterimage.net.PackedPosition
import gg.sona.afterimage.protocol.CameraFrame
import gg.sona.afterimage.protocol.InternalCodec
import gg.sona.afterimage.protocol.LocalBlockChange
import gg.sona.afterimage.protocol.LocalPose
import gg.sona.afterimage.protocol.LocalScreen
import gg.sona.afterimage.protocol.LocalHand
import gg.sona.afterimage.protocol.LocalTarget
import gg.sona.afterimage.protocol.OverlayReset
import gg.sona.afterimage.protocol.ServerboundPacket
import gg.sona.afterimage.replay.consumer.DeliveryMode
import gg.sona.afterimage.replay.consumer.ResetReason
import gg.sona.afterimage.replay.protocol.ReplayState
import gg.sona.afterimage.world.EntityState
import gg.sona.afterimage.world.EntityStates
import gg.sona.afterimage.world.LocalPlayerState
import gg.sona.afterimage.world.PlayerListState
import gg.sona.afterimage.world.RecorderIdentity
import gg.sona.afterimage.world.ScoreboardState
import gg.sona.afterimage.world.WorldEventSink
import gg.sona.afterimage.world.WorldState
import gg.sona.afterimage.world.camera.CameraSample
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.common.ClientboundPostEffectsPacket
import net.minecraft.network.protocol.game.*
import net.minecraft.world.entity.EntityEvent
import net.minecraft.world.entity.PositionMoveRotation
import net.minecraft.world.entity.Relative
import net.minecraft.world.level.GameType
import net.minecraft.world.phys.Vec3
import java.util.UUID

class ShadowClient26(override val identity: RecorderIdentity, knownProfiles: MutableMap<UUID, ShadowPlayer26> = HashMap()) : ReplayState, WorldState, EntityStates {
    val codecs = Codecs26()
    val configuration = ArrayList<CapturedPacket>()
    val level = ShadowLevel26()
    val entityMap = IntObjectMap<ShadowEntity26>(256)
    override val players = ShadowPlayers26(knownProfiles)
    override val scoreboard = ShadowScoreboard26()
    val overlays = ShadowOverlays26()
    override val localPlayer = ShadowLocalPlayer26(identity)
    var login: ClientboundLoginPacket? = null
        private set
    var respawn: ClientboundRespawnPacket? = null
        private set
    var lastNanos: Long = 0L
        private set
    var packetsApplied: Long = 0L
        private set
    var liveEvents: WorldEventSink? = null
    var window: ShadowWindow26? = null
        private set
    var lastDecoded: Pair<CapturedPacket, Packet<*>>? = null
        private set

    override val world: WorldState get() = this
    override val entities: EntityStates get() = this
    override val joined: Boolean get() = login != null && codecs.ready
    override val dimension: Int get() = level.dimension
    override val timeOfDay: Long get() = level.time?.gameTime() ?: 0L
    override val loadedChunks: Int get() = level.chunks.size
    override val trackedPackets: Long get() = packetsApplied
    override val recorderIdentity: RecorderIdentity get() = RecorderIdentity(localPlayer.uuid, localPlayer.name)

    override val size: Int get() = entityMap.size

    override fun get(id: Int): EntityState? = entityMap[id]

    override fun values(): List<EntityState> = entityMap.values()

    override fun blockState(x: Int, y: Int, z: Int): Int = level.blockState(x, y, z)

    override fun dimensionName(dimension: Int): String = Protocol26.dimensionName(dimension)

    override fun reset() {
        codecs.reset()
        configuration.clear()
        level.clear()
        entityMap.clear()
        players.clear()
        scoreboard.clear()
        overlays.clear()
        localPlayer.clear()
        window = null
        lastDecoded = null
        login = null
        respawn = null
        lastNanos = 0L
    }

    override fun observe(packet: CapturedPacket) = apply(packet, liveEvents)

    override fun snapshot(nanos: Long): List<CapturedPacket> = SnapshotEncoder26.encode(this, nanos)

    override fun onReset(reason: ResetReason) = reset()

    override fun onPacket(packet: CapturedPacket, mode: DeliveryMode) = apply(packet, null)

    override fun apply(packet: CapturedPacket, events: WorldEventSink?) {
        lastNanos = packet.timestampNanos
        packetsApplied++
        val nanos = packet.timestampNanos
        if (PacketIds26.isConfiguration(packet.packetId)) {
            if (packet.direction == PacketDirection.CLIENTBOUND) {
                if (login != null && configuration.isNotEmpty() && codecs.ready) {
                    configuration.clear()
                    codecs.reset()
                }
                configuration += packet
                codecs.observeConfiguration(packet)
            }
            return
        }
        if (packet.direction == PacketDirection.SERVERBOUND && InternalCodec.isInternal(packet)) {
            applyInternal(InternalCodec.decode(packet) ?: return, nanos, events)
            return
        }
        val decoded = codecs.decode(packet) ?: return
        lastDecoded = packet to decoded
        if (events != null) PacketEvents26.before(decoded, this, nanos, events)
        when (decoded) {
            is ServerboundContainerClosePacket -> if (window?.id == decoded.containerId) window = null
            is ServerboundSetCarriedItemPacket -> localPlayer.heldSlot = decoded.slot.coerceIn(0, 8)
            else -> applyDecoded(packet, decoded, nanos)
        }
        if (events != null) PacketEvents26.after(decoded, this, nanos, events)
    }

    private fun applyInternal(packet: ServerboundPacket, nanos: Long, events: WorldEventSink?) {
        when (packet) {
            is LocalPose -> localPlayer.setPose(nanos, packet.x, packet.y, packet.z, packet.yaw, packet.pitch)
            is CameraFrame -> localPlayer.cameraFrames.push(CameraSample(nanos, packet.modelView, packet.fov, packet.position, packet.hand))
            is OverlayReset -> overlays.apply(packet, nanos)
            is LocalScreen -> localPlayer.screen = packet
            is LocalTarget -> localPlayer.target = packet
            is LocalHand -> if (packet.isSwing) localPlayer.swings.push(nanos, packet) else if (packet.action == LocalHand.RESET_ATTACK) localPlayer.lastAttackNanos = nanos
            is LocalBlockChange -> level.setBlockState(PackedPosition.x(packet.position), PackedPosition.y(packet.position), PackedPosition.z(packet.position), packet.state)
            else -> Unit
        }
        if (events != null) PacketEvents26.internal(packet, nanos, events)
    }

    fun applyDecoded(source: CapturedPacket, packet: Packet<*>, nanos: Long) {
        when (packet) {
            is ClientboundLoginPacket -> joinGame(packet)
            is ClientboundRespawnPacket -> respawn(packet)
            is ClientboundAddEntityPacket -> entityMap.put(packet.id, ShadowEntity26(packet, nanos, Retained.hashOf(source)))
            is ClientboundRemoveEntitiesPacket -> for (id in packet.entityIds()) destroy(id)
            is ClientboundMoveEntityPacket -> entityMap[(packet as MoveEntityAccessor).afterimage_entityId()]?.let { entity ->
                if (packet.hasPosition()) {
                    val end = packet.positionDelta.decode(entity.positionCodec).endPosition()
                    entity.setPosition(nanos, end.x, end.y, end.z)
                }
                if (packet.hasRotation()) entity.setRotation(nanos, packet.yRot, packet.xRot)
                entity.onGround = packet.isOnGround
            }

            is ClientboundTeleportEntityPacket -> entityMap[packet.id()]?.let { entity ->
                val change = packet.change()
                val relatives = packet.relatives()
                val position = change.position()
                entity.setPosition(
                    nanos,
                    if (Relative.X in relatives) entity.x + position.x else position.x,
                    if (Relative.Y in relatives) entity.y + position.y else position.y,
                    if (Relative.Z in relatives) entity.z + position.z else position.z,
                )
                entity.setRotation(
                    nanos,
                    if (Relative.Y_ROT in relatives) entity.yawDegrees + change.yRot() else change.yRot(),
                    if (Relative.X_ROT in relatives) entity.pitchDegrees + change.xRot() else change.xRot(),
                )
                entity.onGround = packet.onGround()
            }

            is ClientboundEntityPositionSyncPacket -> entityMap[packet.id()]?.let { entity ->
                val end = packet.position().endPosition()
                entity.setPosition(nanos, end.x, end.y, end.z)
                entity.setRotation(nanos, packet.yRot(), packet.xRot())
                entity.onGround = packet.onGround()
            }

            is ClientboundRotateHeadPacket -> entityMap[(packet as RotateHeadAccessor).afterimage_entityId()]?.setHeadYaw(nanos, packet.yHeadRot)
            is ClientboundSetEntityMotionPacket -> entityMap[packet.id()]?.motion = packet
            is ClientboundSetPassengersPacket -> {
                entityMap[packet.vehicle]?.passengers = packet
                entityMap.forEach { _, entity -> if (entity.vehicleId == packet.vehicle) entity.vehicleId = -1 }
                if (localPlayer.vehicleId == packet.vehicle) localPlayer.vehicleId = -1
                for (passenger in packet.passengers) {
                    if (passenger == localPlayer.entityId) localPlayer.vehicleId = packet.vehicle else entityMap[passenger]?.vehicleId = packet.vehicle
                }
            }

            is ClientboundSetEntityDataPacket -> {
                if (packet.id() == localPlayer.entityId) localPlayer.mergeData(packet.packedItems())
                else entityMap[packet.id()]?.mergeData(packet.packedItems())
            }

            is ClientboundSetEquipmentPacket -> entityMap[packet.entity]?.let { entity ->
                for (pair in packet.slots) entity.equipment[pair.first] = pair.second
            }

            is ClientboundUpdateAttributesPacket -> {
                val target = if (packet.entityId == localPlayer.entityId) localPlayer.attributes else entityMap[packet.entityId]?.attributes
                if (target != null) for (snapshot in packet.values) target[snapshot.attribute()] = snapshot
            }

            is ClientboundUpdateMobEffectPacket -> {
                val target = if (packet.entityId == localPlayer.entityId) localPlayer.effects else entityMap[packet.entityId]?.effects
                target?.put(packet.effect, ShadowEffect26(packet, nanos))
            }

            is ClientboundRemoveMobEffectPacket -> {
                if (packet.entityId() == localPlayer.entityId) localPlayer.effects.remove(packet.effect())
                else entityMap[packet.entityId()]?.effects?.remove(packet.effect())
            }

            is ClientboundEntityEventPacket -> {
                val id = (packet as EntityEventAccessor).afterimage_entityId()
                if (packet.eventId == EntityEvent.DEATH) {
                    if (id == localPlayer.entityId) localPlayer.deadAtNanos = nanos
                    else entityMap[id]?.let {
                        if (!it.dead) {
                            it.dead = true
                            it.deadAtNanos = nanos
                        }
                    }
                }
            }

            is ClientboundDamageEventPacket -> {
                if (packet.entityId() == localPlayer.entityId) localPlayer.hurtAtNanos = nanos else entityMap[packet.entityId()]?.hurtAtNanos = nanos
            }

            is ClientboundSwingAnimationPacket -> entityMap[packet.entityId()]?.swings?.push(nanos, packet.hand(), packet.animation())

            is ClientboundPlayerInfoUpdatePacket -> {
                players.apply(packet)
                for (entry in packet.entries()) {
                    if (entry.profileId() == localPlayer.uuid || (localPlayer.uuid == null && entry.profile()?.name == localPlayer.name)) {
                        localPlayer.uuid = entry.profileId()
                        entry.profile()?.name?.let { localPlayer.name = it }
                        if (packet.actions().contains(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_GAME_MODE)) localPlayer.gameType = entry.gameMode()
                    }
                }
            }

            is ClientboundPlayerInfoRemovePacket -> players.apply(packet)
            is ClientboundTabListPacket -> players.tabList = packet
            is ClientboundSetObjectivePacket -> scoreboard.apply(packet)
            is ClientboundSetScorePacket -> scoreboard.apply(packet)
            is ClientboundResetScorePacket -> scoreboard.apply(packet)
            is ClientboundSetDisplayObjectivePacket -> scoreboard.apply(packet)
            is ClientboundSetPlayerTeamPacket -> scoreboard.apply(packet)
            is ClientboundPlayerPositionPacket -> localPlayer.apply(packet, nanos)
            is ClientboundPlayerRotationPacket -> localPlayer.apply(packet, nanos)
            is ClientboundSetHealthPacket -> {
                localPlayer.healthPacket = packet
                if (packet.health > 0f) localPlayer.deadAtNanos = Long.MIN_VALUE
            }

            is ClientboundSetExperiencePacket -> localPlayer.experience = packet
            is ClientboundSetHeldSlotPacket -> localPlayer.heldSlot = packet.slot().coerceIn(0, 8)
            is ClientboundPlayerAbilitiesPacket -> localPlayer.abilities = packet
            is ClientboundContainerSetContentPacket -> {
                localPlayer.apply(packet)
                window?.apply(packet)
            }

            is ClientboundContainerSetSlotPacket -> {
                localPlayer.apply(packet)
                window?.apply(packet)
            }

            is ClientboundOpenScreenPacket -> window = ShadowWindow26(packet.containerId, packet, null, nanos)
            is ClientboundMountScreenOpenPacket -> window = ShadowWindow26(packet.containerId, null, packet, nanos)
            is ClientboundContainerClosePacket -> if (window?.id == packet.containerId) window = null
            is ClientboundContainerSetDataPacket -> window?.apply(packet)
            is ClientboundMerchantOffersPacket -> window?.apply(packet)
            is ClientboundSetCameraPacket -> localPlayer.camera = packet
            is ClientboundGameEventPacket -> {
                level.apply(source, packet, nanos)
                if (packet.event == ClientboundGameEventPacket.CHANGE_GAME_MODE) {
                    GameType.byId(packet.param.toInt())?.let { localPlayer.gameType = it }
                }
            }

            is ClientboundHurtAnimationPacket -> {
                if (packet.id() == localPlayer.entityId) localPlayer.hurtAtNanos = nanos else entityMap[packet.id()]?.hurtAtNanos = nanos
            }

            is ClientboundSetEntityLinkPacket -> entityMap[packet.sourceId]?.link = if (packet.destId == 0) null else packet
            is ClientboundProjectilePowerPacket -> entityMap[packet.id]?.projectilePower = packet
            is ClientboundMoveMinecartPacket -> entityMap[packet.entityId()]?.let { entity ->
                val step = packet.lerpSteps().lastOrNull() ?: return@let
                entity.setPosition(nanos, step.position().x, step.position().y, step.position().z)
                entity.setRotation(nanos, step.yRot(), step.xRot())
            }

            is ClientboundMoveVehiclePacket -> entityMap[localPlayer.vehicleId]?.let { entity ->
                val to = packet.movingTo()
                entity.setPosition(nanos, to.position().x, to.position().y, to.position().z)
                entity.setRotation(nanos, to.yRot(), to.xRot())
            }

            is ClientboundSetPlayerInventoryPacket -> localPlayer.apply(packet)
            is ClientboundSetCursorItemPacket -> {
                localPlayer.apply(packet)
                window?.updateCarried(packet.contents())
            }

            is ClientboundPostEffectsPacket -> localPlayer.postEffects = packet
            is ClientboundSystemChatPacket, is ClientboundPlayerChatPacket, is ClientboundDisguisedChatPacket, is ClientboundSetActionBarTextPacket,
            is ClientboundSetTitleTextPacket, is ClientboundSetSubtitleTextPacket, is ClientboundSetTitlesAnimationPacket, is ClientboundClearTitlesPacket,
                -> overlays.apply(packet, nanos, codecs.registries)

            else -> level.apply(source, packet, nanos)
        }
    }

    private fun joinGame(packet: ClientboundLoginPacket) {
        login = packet
        respawn = null
        level.clear()
        entityMap.clear()
        localPlayer.clear()
        window = null
        localPlayer.entityId = packet.playerId()
        localPlayer.hardcore = packet.hardcore()
        localPlayer.gameType = packet.commonPlayerSpawnInfo().gameType()
        setDimension(packet.commonPlayerSpawnInfo())
    }

    private fun respawn(packet: ClientboundRespawnPacket) {
        respawn = packet
        val key = packet.commonPlayerSpawnInfo().dimension().identifier().toString()
        if (key != level.dimensionKey) {
            level.clearDimension()
            entityMap.clear()
        }
        setDimension(packet.commonPlayerSpawnInfo())
        localPlayer.gameType = packet.commonPlayerSpawnInfo().gameType()
        localPlayer.resetForRespawn()
        window = null
    }

    private fun setDimension(info: CommonPlayerSpawnInfo) {
        level.dimensionKey = info.dimension().identifier().toString()
        level.dimension = Protocol26.dimensionId(level.dimensionKey)
        val type = info.dimensionType().value()
        level.minSectionY = type.minY() shr 4
        level.sectionCount = type.height() shr 4
        level.containers = codecs.containers
    }

    private fun destroy(id: Int) {
        entityMap.remove(id) ?: return
        if (localPlayer.vehicleId == id) localPlayer.vehicleId = -1
        entityMap.forEach { _, other -> if (other.vehicleId == id) other.vehicleId = -1 }
    }

    fun adoptTransients(other: ShadowClient26) {
        localPlayer.adoptTransients(other.localPlayer)
        entityMap.forEach { id, entity -> other.entityMap[id]?.let { entity.adoptTransients(it) } }
    }

    override fun fork(): ReplayState = ShadowClient26(recorderIdentity, players.known)

    override fun canDiff(target: ReplayState): Boolean = target is ShadowClient26 && StateDiff26.canDiff(this, target)

    override fun diff(target: ReplayState, nanos: Long): List<CapturedPacket> = StateDiff26.packets(this, target as ShadowClient26, nanos)

    override fun diff(target: ReplayState, nanos: Long, freshEntities: Boolean): List<CapturedPacket> {
        if (freshEntities) freshRecorderPending = true
        return StateDiff26.packets(this, target as ShadowClient26, nanos, freshEntities)
    }

    private var freshRecorderPending = false

    fun consumeFreshRecorder(): Boolean {
        val pending = freshRecorderPending
        freshRecorderPending = false
        return pending
    }


    override fun adoptFrom(target: ReplayState, nanos: Long) {
        val other = target as ShadowClient26
        localPlayer.cameraFrames.copyFrom(other.localPlayer.cameraFrames)
        localPlayer.cameraFrames.dropAfter(nanos)
        localPlayer.history.copyFrom(other.localPlayer.history)
        adoptTransients(other)
    }

    override fun dropFutureCameraFrames(nanos: Long) = localPlayer.cameraFrames.dropAfter(nanos)

    fun currentPositionPacket(): ClientboundPlayerPositionPacket =
        ClientboundPlayerPositionPacket(0, PositionMoveRotation(Vec3(localPlayer.x, localPlayer.y, localPlayer.z), Vec3.ZERO, localPlayer.yaw, localPlayer.pitch), java.util.EnumSet.noneOf(Relative::class.java))
}
