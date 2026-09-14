package gg.sona.afterimage.replay.perspective

import gg.sona.afterimage.net.CapturedPacket
import gg.sona.afterimage.net.PackedPosition
import gg.sona.afterimage.net.PacketDirection
import gg.sona.afterimage.net.PacketWriter
import gg.sona.afterimage.protocol.*
import gg.sona.afterimage.replay.consumer.DeliveryMode
import gg.sona.afterimage.replay.consumer.ReplayConsumer
import gg.sona.afterimage.replay.consumer.ResetReason
import gg.sona.afterimage.replay.state.shadow.ShadowClient
import java.util.*

class RecorderProjection(
    private val shadow: ShadowClient,
    private val downstream: ReplayConsumer,
    private val options: ProjectionOptions = ProjectionOptions(),
) : ReplayConsumer {

    private val writer = PacketWriter(512)
    private val pending = ArrayList<CapturedPacket>()
    private val pendingDigs = HashMap<Long, Int>()
    private val lastEquipment = arrayOfNulls<ItemStack>(5)
    private val footsteps = RecorderFootsteps { x, y, z -> shadow.world.blockState(x, y, z) }

    var recorderEntityId: Int = -1
        private set

    var recorderSpawned: Boolean = false
        private set

    private var cameraPlaced = false
    private var dimension = Int.MIN_VALUE

    override fun onReset(reason: ResetReason) {
        recorderSpawned = false
        cameraPlaced = false
        pending.clear()
        lastEquipment.fill(null)
        footsteps.reset()
        downstream.onReset(reason)
    }

    override fun onSettled(positionNanos: Long, mode: DeliveryMode) = downstream.onSettled(positionNanos, mode)

    override fun onPacket(packet: CapturedPacket, mode: DeliveryMode) {
        if (packet.direction == PacketDirection.SERVERBOUND) outbound(packet, mode) else inbound(packet, mode)
    }

    private fun inbound(packet: CapturedPacket, mode: DeliveryMode) {
        if (AfterimageInternal.isInternal(packet.packetId)) {
            if (packet.packetId == AfterimageInternal.OVERLAY_RESET) forward(packet, mode)
            return
        }
        when (packet.packetId) {
            ClientboundPlay.CHANGE_GAME_STATE -> {
                val state = PacketCodec.decode(packet) as? ChangeGameState ?: return
                if (state.reason != ChangeGameState.CHANGE_GAME_MODE) forward(packet, mode)
            }

            ClientboundPlay.JOIN_GAME -> {
                val join = PacketCodec.decode(packet) as? JoinGame ?: return
                recorderEntityId = join.entityId
                dimension = join.dimension
                recorderSpawned = false
                cameraPlaced = false
                pending.clear()
                lastEquipment.fill(null)
                footsteps.reset()
                emit(
                    join.copy(
                        entityId = options.cameraEntityId,
                        gameMode = options.cameraGameMode,
                        reducedDebugInfo = false
                    ), packet, mode
                )
                emit(PlayerAbilities(options.cameraAbilities, 0.05f, 0.1f), packet, mode)
            }

            ClientboundPlay.BLOCK_CHANGE -> {
                forward(packet, mode)
                if (pendingDigs.isNotEmpty()) {
                    val change = PacketCodec.decode(packet) as? BlockChange
                    if (change != null && change.blockState == 0) {
                        val state = pendingDigs.remove(change.position)
                        if (state != null && state != 0) emit(
                            WorldEffect(
                                BLOCK_BREAK_EFFECT,
                                change.position,
                                effectData(state),
                                false
                            ), packet, mode
                        )
                    }
                }
            }

            ClientboundPlay.RESPAWN -> {
                val respawn = PacketCodec.decode(packet) as? Respawn ?: return
                if (respawn.dimension != dimension) {
                    pending.clear()
                    footsteps.reset()
                }
                recorderSpawned = false
                dimension = respawn.dimension
                cameraPlaced = false
                emit(respawn.copy(gameMode = options.cameraGameMode), packet, mode)
            }

            ClientboundPlay.PLAYER_POSITION_AND_LOOK -> {
                val player = shadow.localPlayer
                if (!recorderSpawned) spawnRecorder(packet, mode) else teleportRecorder(packet, mode)
                footsteps.reposition(player.x, player.y, player.z)
                if (!cameraPlaced) {
                    emit(PlayerPositionAndLook(player.x, player.y, player.z, player.yaw, player.pitch, 0), packet, mode)
                    cameraPlaced = true
                }
            }

            ClientboundPlay.HELD_ITEM_CHANGE -> {
                if (options.mirrorHud) forward(packet, mode)
                syncEquipment(packet, mode)
            }

            ClientboundPlay.WINDOW_ITEMS -> {
                if (options.mirrorHud) {
                    val items = PacketCodec.decode(packet) as? WindowItems
                    if (items?.windowId == 0) forward(packet, mode)
                    else if (items != null && items.windowId == shadow.localPlayer.openWindowId) emit(
                        WindowItems(
                            0,
                            shadow.localPlayer.inventory.toList()
                        ), packet, mode
                    )
                }
                syncEquipment(packet, mode)
            }

            ClientboundPlay.SET_SLOT -> {
                if (options.mirrorHud) {
                    val slot = PacketCodec.decode(packet) as? SetSlot
                    if (slot != null && (slot.windowId == 0 || slot.windowId == -1)) forward(packet, mode)
                    else if (slot != null && slot.windowId == shadow.localPlayer.openWindowId) {
                        val playerSlot = slot.slot - (shadow.localPlayer.openWindowSize - PLAYER_SLOTS_IN_CONTAINER)
                        if (playerSlot in 0 until PLAYER_SLOTS_IN_CONTAINER) emit(
                            SetSlot(
                                0,
                                MAIN_INVENTORY_START + playerSlot,
                                slot.item
                            ), packet, mode
                        )
                    }
                }
                syncEquipment(packet, mode)
            }

            ClientboundPlay.UPDATE_HEALTH -> if (options.mirrorHud) {
                val health = PacketCodec.decode(packet) as? UpdateHealth ?: return
                emit(
                    health.copy(health = if (health.health <= 0f) MIN_MIRRORED_HEALTH else health.health),
                    packet,
                    mode
                )
            }

            ClientboundPlay.SET_EXPERIENCE -> if (options.mirrorHud) forward(packet, mode)
            ClientboundPlay.DESTROY_ENTITIES -> {
                val destroy = PacketCodec.decode(packet) as? DestroyEntities ?: return
                if (destroy.entityIds.none { it == recorderEntityId }) forward(packet, mode)
                else emit(
                    DestroyEntities(destroy.entityIds.filter { it != recorderEntityId }.toIntArray()),
                    packet,
                    mode
                )
            }

            ClientboundPlay.PLAYER_ABILITIES,
            ClientboundPlay.OPEN_WINDOW,
            ClientboundPlay.CLOSE_WINDOW,
            ClientboundPlay.WINDOW_PROPERTY,
            ClientboundPlay.CONFIRM_TRANSACTION,
            ClientboundPlay.KEEP_ALIVE,
            ClientboundPlay.SET_COMPRESSION,
            ClientboundPlay.DISCONNECT,
            ClientboundPlay.RESOURCE_PACK_SEND,
            ClientboundPlay.CAMERA,
            ClientboundPlay.STATISTICS,
            ClientboundPlay.TAB_COMPLETE,
            ClientboundPlay.OPEN_SIGN_EDITOR,
                -> Unit

            else -> {
                val target = EntityPacketIds.targetOf(packet)
                if (target == recorderEntityId && !recorderSpawned) {
                    if (pending.size < options.maxPendingPackets) pending += packet
                } else {
                    forward(packet, mode)
                    if (target == recorderEntityId) {
                        recorderSound(packet, mode)
                        if (options.mirrorHud) mirrorToCamera(packet, mode)
                    }
                }
            }
        }
    }

    private fun footstep(onGround: Boolean, reference: CapturedPacket, mode: DeliveryMode) {
        val player = shadow.localPlayer
        val step = footsteps.move(
            player.x,
            player.y,
            player.z,
            onGround,
            player.sneaking,
            player.vehicleId != -1,
            reference.timestampNanos
        ) ?: return
        if (mode != DeliveryMode.LIVE) return
        emit(
            SoundEffect(
                step.sound,
                (player.x * 8).toInt(),
                (player.y * 8).toInt(),
                (player.z * 8).toInt(),
                step.volume,
                (step.pitch * 63f).toInt()
            ), reference, mode
        )
    }

    private fun recorderSound(packet: CapturedPacket, mode: DeliveryMode) {
        if (packet.packetId != ClientboundPlay.ENTITY_STATUS) return
        val status = PacketCodec.decode(packet) as? EntityStatus ?: return
        val sound = when (status.status) {
            EntityStatus.HURT -> "game.player.hurt"
            EntityStatus.DEAD -> "game.player.die"
            else -> return
        }
        val player = shadow.localPlayer
        if (!player.hasPosition) return
        val pitch = 1f + ((packet.timestampNanos ushr 16) % 41L - 20L) / 100f
        emit(
            SoundEffect(
                sound,
                (player.x * 8).toInt(),
                (player.y * 8).toInt(),
                (player.z * 8).toInt(),
                1f,
                (pitch * 63f).toInt()
            ), packet, mode
        )
    }

    private fun mirrorToCamera(packet: CapturedPacket, mode: DeliveryMode) {
        when (packet.packetId) {
            ClientboundPlay.ENTITY_EFFECT, ClientboundPlay.REMOVE_ENTITY_EFFECT -> forward(
                EntityPacketIds.rewriteTarget(
                    packet,
                    options.cameraEntityId
                ), mode
            )

            ClientboundPlay.ANIMATION -> {
                val animation = PacketCodec.decode(packet) as? Animation ?: return
                if (animation.animation == Animation.SWING_ARM) emit(
                    animation.copy(entityId = options.cameraEntityId),
                    packet,
                    mode
                )
            }

            ClientboundPlay.ENTITY_METADATA -> {
                val metadata = PacketCodec.decode(packet) as? EntityMetadata ?: return
                val entries = ProjectionOptions.cameraMetadata(metadata.metadata)
                if (entries.isNotEmpty()) emit(EntityMetadata(options.cameraEntityId, entries), packet, mode)
            }
        }
    }

    private fun outbound(packet: CapturedPacket, mode: DeliveryMode) {
        when (val decoded = PacketCodec.decode(packet)) {
            is LocalPose -> if (recorderSpawned) teleportRecorder(packet, mode)
            is ClientPlayerPosition -> if (recorderSpawned) {
                teleportRecorder(packet, mode)
                footstep(decoded.onGround, packet, mode)
            }

            is ClientPlayerPositionAndLook -> if (recorderSpawned) {
                teleportRecorder(packet, mode)
                footstep(decoded.onGround, packet, mode)
            }

            is ClientPlayerLook -> if (recorderSpawned) {
                val player = shadow.localPlayer
                emit(
                    EntityLook(
                        recorderEntityId,
                        Protocol.toAngle(player.yaw),
                        Protocol.toAngle(player.pitch),
                        player.onGround
                    ), packet, mode
                )
                emit(EntityHeadLook(recorderEntityId, Protocol.toAngle(player.yaw)), packet, mode)
            }

            is EntityAction -> if (recorderSpawned && decoded.action <= EntityAction.STOP_SPRINTING) {
                emit(
                    EntityMetadata(recorderEntityId, listOf(MetadataEntry.ofByte(0, shadow.localPlayer.flagsByte()))),
                    packet,
                    mode
                )
            }

            ClientArmSwing -> if (recorderSpawned) {
                emit(Animation(recorderEntityId, Animation.SWING_ARM), packet, mode)
                if (options.mirrorHud) emit(Animation(options.cameraEntityId, Animation.SWING_ARM), packet, mode)
            }

            is ClientHeldItemChange -> {
                if (options.mirrorHud) emit(HeldItemChange(decoded.slot), packet, mode)
                syncEquipment(packet, mode)
            }

            is LocalBlockBreak -> if (recorderSpawned) {
                emit(BlockBreakAnimation(recorderEntityId, decoded.position, decoded.stage), packet, mode)
                forward(packet, mode)
            }

            is LocalBlockChange -> emit(BlockChange(decoded.position, decoded.state), packet, mode)

            is PlayerDigging -> if (recorderSpawned) {
                val current = shadow.world.blockState(
                    PackedPosition.x(decoded.position),
                    PackedPosition.y(decoded.position),
                    PackedPosition.z(decoded.position)
                )
                when (decoded.status) {
                    PlayerDigging.START_DIGGING -> {
                        if (pendingDigs.size > MAX_PENDING_DIGS) pendingDigs.clear()
                        if (current != 0) pendingDigs[decoded.position] = current
                        if (shadow.localPlayer.gameMode == CREATIVE_MODE && current != 0) breakBlock(
                            decoded.position,
                            current,
                            packet,
                            mode
                        )
                    }

                    PlayerDigging.CANCEL_DIGGING -> pendingDigs.remove(decoded.position)
                    PlayerDigging.FINISH_DIGGING -> {
                        val state = pendingDigs.remove(decoded.position) ?: current
                        if (state != 0) breakBlock(decoded.position, state, packet, mode)
                    }

                    else -> Unit
                }
            }

            is PlayerBlockPlacement -> if (recorderSpawned && mode == DeliveryMode.LIVE && options.predictBlocks && decoded.face in 0..5) {
                val held = decoded.heldItem
                if (!held.isEmpty && held.id in 1..255 && held.id !in ORIENTED) {
                    val x = PackedPosition.x(decoded.position)
                    val y = PackedPosition.y(decoded.position)
                    val z = PackedPosition.z(decoded.position)
                    val clicked = shadow.world.blockState(x, y, z)
                    val activates = (clicked shr 4) in INTERACTIVE && !shadow.localPlayer.sneaking
                    if (!activates) {
                        val target = if ((clicked shr 4) in REPLACEABLE) decoded.position else PackedPosition.pack(
                            x + FACE_X[decoded.face],
                            y + FACE_Y[decoded.face],
                            z + FACE_Z[decoded.face]
                        )
                        val existing = shadow.world.blockState(
                            PackedPosition.x(target),
                            PackedPosition.y(target),
                            PackedPosition.z(target)
                        )
                        if (existing == 0 || (existing shr 4) in REPLACEABLE) predictBlock(
                            target,
                            (held.id shl 4) or (held.damage and 15),
                            packet,
                            mode
                        )
                    }
                }
            }

            else -> Unit
        }
    }

    private fun breakBlock(position: Long, state: Int, packet: CapturedPacket, mode: DeliveryMode) {
        emit(WorldEffect(BLOCK_BREAK_EFFECT, position, effectData(state), false), packet, mode)
        if (mode == DeliveryMode.LIVE && options.predictBlocks) predictBlock(position, 0, packet, mode)
    }

    private fun predictBlock(position: Long, state: Int, packet: CapturedPacket, mode: DeliveryMode) {
        shadow.world.setBlockState(
            PackedPosition.x(position),
            PackedPosition.y(position),
            PackedPosition.z(position),
            state
        )
        emit(BlockChange(position, state), packet, mode)
    }

    private fun effectData(packedState: Int): Int = (packedState shr 4) or ((packedState and 15) shl 12)

    private fun spawnRecorder(reference: CapturedPacket, mode: DeliveryMode) {
        val player = shadow.localPlayer
        if (!player.hasPosition || recorderEntityId == -1) return
        val uuid = player.uuid ?: UUID.nameUUIDFromBytes("OfflinePlayer:${player.name ?: "Afterimage"}".toByteArray())
        val listed = shadow.players.entries.containsKey(uuid)
        val profile = shadow.players.profile(uuid)?.toPacketEntry()
            ?: PlayerListEntry(uuid, player.name ?: "Afterimage", emptyList(), player.gameMode, 0, null)
        if (!listed) emit(PlayerListItem(PlayerListItem.ADD_PLAYER, listOf(profile)), reference, mode)
        val held = player.heldItem
        emit(
            SpawnPlayer(
                recorderEntityId,
                uuid,
                Protocol.toFixed(player.x),
                Protocol.toFixed(player.y),
                Protocol.toFixed(player.z),
                Protocol.toAngle(player.yaw),
                Protocol.toAngle(player.pitch),
                if (held.isEmpty) 0 else held.id,
                player.entityMetadata(),
            ),
            reference,
            mode,
        )
        emit(EntityHeadLook(recorderEntityId, Protocol.toAngle(player.yaw)), reference, mode)
        recorderSpawned = true
        for (queued in pending) forward(queued, mode)
        pending.clear()
        lastEquipment.fill(null)
        syncEquipment(reference, mode)
        if (player.attributes.isNotEmpty()) emit(
            EntityProperties(recorderEntityId, player.attributes.values.toList()),
            reference,
            mode
        )
        player.effects.forEach { _, effect ->
            val remaining = effect.remainingTicks(reference.timestampNanos)
            if (remaining > 0) emit(
                EntityEffect(
                    recorderEntityId,
                    effect.effectId,
                    effect.amplifier,
                    remaining,
                    effect.hideParticles
                ), reference, mode
            )
        }
        if (options.mirrorHud) {
            val entries = ProjectionOptions.cameraMetadata(player.entityMetadata())
            if (entries.isNotEmpty()) emit(EntityMetadata(options.cameraEntityId, entries), reference, mode)
        }
        if (!listed) emit(PlayerListItem(PlayerListItem.REMOVE_PLAYER, listOf(profile)), reference, mode)
    }

    private fun teleportRecorder(reference: CapturedPacket, mode: DeliveryMode) {
        val player = shadow.localPlayer
        emit(
            EntityTeleport(
                recorderEntityId,
                Protocol.toFixed(player.x),
                Protocol.toFixed(player.y),
                Protocol.toFixed(player.z),
                Protocol.toAngle(player.yaw),
                Protocol.toAngle(player.pitch),
                player.onGround,
            ),
            reference,
            mode,
        )
        emit(EntityHeadLook(recorderEntityId, Protocol.toAngle(player.yaw)), reference, mode)
    }

    private fun syncEquipment(reference: CapturedPacket, mode: DeliveryMode) {
        if (!recorderSpawned) return
        val player = shadow.localPlayer
        for (slot in 0 until 5) {
            val item = player.equipmentItem(slot)
            val previous = lastEquipment[slot]
            if (previous != null && sameItem(previous, item)) continue
            if (previous == null && item.isEmpty) {
                lastEquipment[slot] = item
                continue
            }
            lastEquipment[slot] = item
            emit(EntityEquipment(recorderEntityId, slot, item), reference, mode)
        }
    }

    private fun sameItem(a: ItemStack, b: ItemStack): Boolean =
        a.id == b.id && a.count == b.count && a.damage == b.damage && a.tag === b.tag

    private fun emit(packet: PlayPacket, reference: CapturedPacket, mode: DeliveryMode) {
        forward(PacketCodec.encode(packet, reference.timestampNanos, writer), mode)
    }

    private fun forward(packet: CapturedPacket, mode: DeliveryMode) = downstream.onPacket(packet, mode)

    private companion object {
        const val CREATIVE_MODE = 1
        val ORIENTED = setOf(
            17,
            162,
            23,
            158,
            27,
            28,
            66,
            157,
            29,
            33,
            44,
            126,
            182,
            50,
            75,
            76,
            53,
            67,
            108,
            109,
            114,
            128,
            134,
            135,
            136,
            156,
            163,
            164,
            180,
            54,
            146,
            130,
            61,
            65,
            69,
            77,
            143,
            86,
            91,
            96,
            167,
            106,
            107,
            183,
            184,
            185,
            186,
            187,
            120,
            131,
            145,
            154,
            155,
            170,
            175,
            78,
            137
        )
        val INTERACTIVE = setOf(
            23,
            25,
            26,
            54,
            146,
            130,
            58,
            61,
            62,
            64,
            71,
            193,
            194,
            195,
            196,
            197,
            69,
            77,
            143,
            84,
            92,
            93,
            94,
            149,
            150,
            96,
            167,
            107,
            183,
            184,
            185,
            186,
            187,
            116,
            117,
            118,
            122,
            137,
            138,
            140,
            145,
            151,
            178,
            154,
            158
        )
        val REPLACEABLE = setOf(0, 8, 9, 10, 11, 31, 32, 51, 78)
        val FACE_X = intArrayOf(0, 0, 0, 0, -1, 1)
        val FACE_Y = intArrayOf(-1, 1, 0, 0, 0, 0)
        val FACE_Z = intArrayOf(0, 0, -1, 1, 0, 0)
        const val BLOCK_BREAK_EFFECT = 2001
        const val MAX_PENDING_DIGS = 64
        const val MIN_MIRRORED_HEALTH = 0.01f
        const val PLAYER_SLOTS_IN_CONTAINER = 36
        const val MAIN_INVENTORY_START = 9
    }
}
