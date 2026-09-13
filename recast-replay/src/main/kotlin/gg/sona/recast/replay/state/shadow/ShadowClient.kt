package gg.sona.recast.replay.state.shadow

import gg.sona.recast.capture.state.StateTracker
import gg.sona.recast.core.collect.IntObjectMap
import gg.sona.recast.net.CapturedPacket
import gg.sona.recast.net.PackedPosition
import gg.sona.recast.protocol.*
import gg.sona.recast.replay.consumer.DeliveryMode
import gg.sona.recast.replay.consumer.ReplayConsumer
import gg.sona.recast.replay.consumer.ResetReason
import gg.sona.recast.replay.state.camera.CameraSample
import gg.sona.recast.replay.state.diff.SnapshotEncoder

class ShadowClient(identity: RecorderIdentity = RecorderIdentity.UNKNOWN) : StateTracker, ReplayConsumer {
    val world = ShadowWorld()
    val entities = IntObjectMap<ShadowEntity>(256)
    val players = ShadowPlayerList()
    val scoreboard = ShadowScoreboard()
    val overlays = ShadowOverlays()
    val localPlayer = ShadowLocalPlayer().apply {
        uuid = identity.uuid
        name = identity.name
    }

    var joined: Boolean = false
    var resourcePack: ResourcePackSend? = null
        private set

    var lastNanos: Long = 0L
        private set

    var packetsApplied: Long = 0L
        private set

    val recorderIdentity: RecorderIdentity
        get() = RecorderIdentity(localPlayer.uuid, localPlayer.name)

    override fun reset() {
        world.clear()
        entities.clear()
        players.clear()
        players.knownProfiles.clear()
        scoreboard.clear()
        overlays.clear()
        localPlayer.clear()
        joined = false
        lastNanos = 0L
    }

    override fun observe(packet: CapturedPacket) = apply(packet)

    override fun snapshot(nanos: Long): List<CapturedPacket> = SnapshotEncoder.encode(this, nanos)

    override fun onReset(reason: ResetReason) = reset()

    override fun onPacket(packet: CapturedPacket, mode: DeliveryMode) = apply(packet)

    fun apply(packet: CapturedPacket) {
        lastNanos = packet.timestampNanos
        packetsApplied++
        val decoded = PacketCodec.decode(packet) ?: return
        apply(decoded, packet.timestampNanos)
    }

    fun apply(packet: PlayPacket, nanos: Long) {
        when (packet) {
            is JoinGame -> joinGame(packet)
            is ChatMessage -> overlays.apply(packet, nanos)
            is Title -> overlays.apply(packet, nanos)
            is OverlayReset -> overlays.apply(packet, nanos)
            is Respawn -> respawn(packet)
            is TimeUpdate -> {
                world.worldAge = packet.worldAge
                world.timeOfDay = packet.timeOfDay
                world.timeUpdatedAtNanos = nanos
                world.hasTime = true
            }

            is SpawnPosition -> {
                world.spawnPosition = packet.position
                world.hasSpawn = true
            }

            is ServerDifficulty -> world.difficulty = packet.difficulty
            is WorldBorder -> world.border.apply(packet, nanos)
            is ChangeGameState -> gameState(packet)
            is ChunkData -> chunkData(packet, nanos)
            is MapChunkBulk -> for ((chunkX, chunkZ, mask, data) in packet.chunks) world.loadChunk(
                chunkX,
                chunkZ,
                mask,
                data, packet.skyLight, true, nanos
            )

            is BlockChange -> world.setBlockState(
                PackedPosition.x(packet.position),
                PackedPosition.y(packet.position),
                PackedPosition.z(packet.position),
                packet.blockState
            )

            is MultiBlockChange -> {
                val baseX = packet.chunkX shl 4
                val baseZ = packet.chunkZ shl 4
                for ((x, y, z, blockState) in packet.records) world.setBlockState(baseX + x, y, baseZ + z, blockState)
            }

            is UpdateBlockEntity -> {
                if (packet.nbt == null) world.blockEntities.remove(packet.position)
                else world.blockEntities.put(
                    packet.position,
                    BlockEntityRecord(packet.position, packet.action, packet.nbt)
                )
            }

            is UpdateSign -> world.signs.put(packet.position, SignRecord(packet.position, packet.lines))
            is MapData -> mapData(packet)
            is SpawnPlayer -> spawnPlayer(packet, nanos)
            is SpawnObject -> spawnObject(packet, nanos)
            is SpawnMob -> spawnMob(packet, nanos)
            is SpawnPainting -> {
                val entity = spawn(packet.entityId, EntityKind.PAINTING, nanos)
                entity.paintingTitle = packet.title
                entity.paintingPosition = packet.position
                entity.paintingFacing = packet.facing
                entity.setPosition(
                    nanos,
                    PackedPosition.x(packet.position) * 32,
                    PackedPosition.y(packet.position) * 32,
                    PackedPosition.z(packet.position) * 32
                )
            }

            is SpawnExperienceOrb -> {
                val entity = spawn(packet.entityId, EntityKind.EXPERIENCE_ORB, nanos)
                entity.orbCount = packet.count
                entity.setPosition(nanos, packet.x, packet.y, packet.z)
            }

            is SpawnGlobalEntity -> {
                val entity = spawn(packet.entityId, EntityKind.GLOBAL, nanos)
                entity.type = packet.type
                entity.setPosition(nanos, packet.x, packet.y, packet.z)
            }

            is DestroyEntities -> for (id in packet.entityIds) destroy(id)
            is EntityRelativeMove -> entities[packet.entityId]?.let {
                it.move(nanos, packet.deltaX.toInt(), packet.deltaY.toInt(), packet.deltaZ.toInt())
                it.onGround = packet.onGround
            }

            is EntityLook -> entities[packet.entityId]?.let {
                it.setRotation(nanos, packet.yaw, packet.pitch)
                it.onGround = packet.onGround
            }

            is EntityLookAndRelativeMove -> entities[packet.entityId]?.let {
                it.setRotation(nanos, packet.yaw, packet.pitch)
                it.move(nanos, packet.deltaX.toInt(), packet.deltaY.toInt(), packet.deltaZ.toInt())
                it.onGround = packet.onGround
            }

            is EntityTeleport -> entities[packet.entityId]?.let {
                it.setPose(nanos, packet.x, packet.y, packet.z, packet.yaw, packet.pitch)
                it.onGround = packet.onGround
            }

            is EntityHeadLook -> entities[packet.entityId]?.setHeadYaw(nanos, packet.headYaw)
            is EntityVelocity -> entities[packet.entityId]?.let {
                it.velocityX = packet.velocityX
                it.velocityY = packet.velocityY
                it.velocityZ = packet.velocityZ
            }

            is EntityStatus -> entities[packet.entityId]?.let {
                it.lastStatus = packet.status
                if (packet.status == EntityStatus.DEAD) it.dead = true
            }

            is AttachEntity -> attach(packet)
            is EntityMetadata -> {
                if (packet.entityId == localPlayer.entityId) localMetadata(packet.metadata)
                else entities[packet.entityId]?.mergeMetadata(packet.metadata)
            }

            is EntityEquipment -> entities[packet.entityId]?.let {
                if (packet.slot in 0..4) it.equipment[packet.slot] = packet.item
            }

            is EntityEffect -> {
                val effects =
                    if (packet.entityId == localPlayer.entityId) localPlayer.effects else entities[packet.entityId]?.effects
                effects?.put(
                    packet.effectId,
                    ShadowEffect(packet.effectId, packet.amplifier, packet.duration, packet.hideParticles, nanos)
                )
            }

            is RemoveEntityEffect -> {
                if (packet.entityId == localPlayer.entityId) localPlayer.effects.remove(packet.effectId)
                else entities[packet.entityId]?.effects?.remove(packet.effectId)
            }

            is EntityProperties -> {
                val target =
                    if (packet.entityId == localPlayer.entityId) localPlayer.attributes else entities[packet.entityId]?.attributes
                if (target != null) for (attribute in packet.attributes) target[attribute.key] = attribute
            }

            is UpdateEntityNbt -> entities[packet.entityId]?.nbt = packet.nbt
            is PlayerListItem -> {
                players.apply(packet)
                if (packet.action == PlayerListItem.ADD_PLAYER) for ((uuid, name) in packet.entries) {
                    if (uuid == localPlayer.uuid || (localPlayer.uuid == null && name == localPlayer.name)) {
                        localPlayer.uuid = uuid
                        localPlayer.name = name
                    }
                }
            }

            is PlayerListHeaderFooter -> {
                players.headerJson = packet.headerJson
                players.footerJson = packet.footerJson
            }

            is ScoreboardObjective -> scoreboard.apply(packet)
            is UpdateScore -> scoreboard.apply(packet)
            is DisplayScoreboard -> scoreboard.apply(packet)
            is Teams -> scoreboard.apply(packet)
            is PlayerPositionAndLook -> positionAndLook(packet, nanos)
            is UpdateHealth -> {
                localPlayer.health = packet.health
                localPlayer.food = packet.food
                localPlayer.saturation = packet.saturation
            }

            is SetExperience -> {
                localPlayer.xpBar = packet.bar
                localPlayer.xpLevel = packet.level
                localPlayer.xpTotal = packet.total
            }

            is HeldItemChange -> localPlayer.heldSlot = packet.slot.coerceIn(0, 8)
            is ClientHeldItemChange -> localPlayer.heldSlot = packet.slot.coerceIn(0, 8)
            is PlayerAbilities -> {
                localPlayer.abilityFlags = packet.flags
                localPlayer.flyingSpeed = packet.flyingSpeed
                localPlayer.walkingSpeed = packet.walkingSpeed
            }

            is OpenWindow -> openWindow(packet)
            is ResourcePackSend -> resourcePack = packet
            is PluginMessage -> if (packet.channel == "MC|TrList" && packet.data.size >= 4) {
                val windowId =
                    ((packet.data[0].toInt() and 0xFF) shl 24) or ((packet.data[1].toInt() and 0xFF) shl 16) or ((packet.data[2].toInt() and 0xFF) shl 8) or (packet.data[3].toInt() and 0xFF)
                localPlayer.window?.takeIf { it.id == windowId }?.setTrades(packet.data)
            }

            is CloseWindow -> closeWindow()
            is WindowProperty -> localPlayer.window?.takeIf { it.id == packet.windowId }
                ?.setProperty(packet.property, packet.value)

            is SetSlot -> setSlot(packet)
            is WindowItems -> windowItems(packet)

            is Camera -> localPlayer.cameraEntityId =
                if (packet.cameraEntityId == localPlayer.entityId) -1 else packet.cameraEntityId

            is ClientPlayerPosition -> {
                localPlayer.setPosition(nanos, packet.x, packet.y, packet.z)
                localPlayer.onGround = packet.onGround
            }

            is ClientPlayerLook -> {
                localPlayer.setRotation(nanos, packet.yaw, packet.pitch)
                localPlayer.onGround = packet.onGround
            }

            is ClientPlayerPositionAndLook -> {
                localPlayer.setPose(nanos, packet.x, packet.y, packet.z, packet.yaw, packet.pitch)
                localPlayer.onGround = packet.onGround
            }

            is ClientPlayerGround -> localPlayer.onGround = packet.onGround
            is LocalPose -> localPlayer.setPose(nanos, packet.x, packet.y, packet.z, packet.yaw, packet.pitch)
            is LocalScreen -> localPlayer.screen = packet
            is LocalBlockChange -> world.setBlockState(
                PackedPosition.x(packet.position),
                PackedPosition.y(packet.position),
                PackedPosition.z(packet.position),
                packet.state
            )

            is LocalTarget -> localPlayer.target = packet
            is ClientCloseWindow -> closeWindow()
            is CameraFrame -> localPlayer.cameraFrames.push(
                CameraSample(
                    nanos,
                    packet.modelView,
                    packet.fov,
                    packet.position,
                    packet.hand
                )
            )

            is EntityAction -> when (packet.action) {
                EntityAction.START_SNEAKING -> localPlayer.sneaking = true
                EntityAction.STOP_SNEAKING -> localPlayer.sneaking = false
                EntityAction.START_SPRINTING -> localPlayer.sprinting = true
                EntityAction.STOP_SPRINTING -> localPlayer.sprinting = false
            }

            else -> Unit
        }
    }

    private fun joinGame(packet: JoinGame) {
        world.clear()
        entities.clear()
        localPlayer.clear()
        localPlayer.entityId = packet.entityId
        localPlayer.gameMode = packet.gameMode
        localPlayer.reducedDebugInfo = packet.reducedDebugInfo
        world.dimension = packet.dimension
        world.difficulty = packet.difficulty
        world.maxPlayers = packet.maxPlayers
        world.levelType = packet.levelType
        joined = true
    }

    private fun respawn(packet: Respawn) {
        if (packet.dimension != world.dimension) {
            world.clear()
            entities.clear()
            localPlayer.hasPosition = false
            localPlayer.history.clear()
        }
        world.dimension = packet.dimension
        world.difficulty = packet.difficulty
        world.levelType = packet.levelType
        localPlayer.gameMode = packet.gameMode
        localPlayer.resetForRespawn()
    }

    private fun gameState(packet: ChangeGameState) {
        when (packet.reason) {
            ChangeGameState.END_RAINING -> {
                world.raining = false
                world.rainStrength = 0f
            }

            ChangeGameState.BEGIN_RAINING -> world.raining = true
            ChangeGameState.CHANGE_GAME_MODE -> localPlayer.gameMode = packet.value.toInt()
            ChangeGameState.RAIN_STRENGTH -> world.rainStrength = packet.value
            ChangeGameState.THUNDER_STRENGTH -> world.thunderStrength = packet.value
        }
    }

    private fun chunkData(packet: ChunkData, nanos: Long) {
        if (packet.isUnload) {
            world.unloadChunk(packet.chunkX, packet.chunkZ)
            return
        }
        world.loadChunk(
            packet.chunkX,
            packet.chunkZ,
            packet.mask,
            packet.data,
            world.hasSkyLight,
            packet.groundUp,
            nanos
        )
    }

    private fun mapData(packet: MapData) {
        val canvas = world.maps.getOrPut(packet.mapId) { MapCanvas(packet.mapId) }
        canvas.scale = packet.scale
        canvas.icons = packet.icons
        if (packet.columns > 0) {
            canvas.hasPixels = true
            for (column in 0 until packet.columns) {
                for (row in 0 until packet.rows) {
                    val source = column + row * packet.columns
                    val x = packet.x + column
                    val z = packet.z + row
                    if (x in 0..127 && z in 0..127 && source < packet.data.size) canvas.pixels[x + z * 128] =
                        packet.data[source]
                }
            }
        }
    }

    private fun spawn(id: Int, kind: EntityKind, nanos: Long): ShadowEntity {
        val entity = ShadowEntity(id, kind, nanos)
        entities.put(id, entity)
        return entity
    }

    private fun spawnPlayer(packet: SpawnPlayer, nanos: Long) {
        val entity = spawn(packet.entityId, EntityKind.PLAYER, nanos)
        entity.uuid = packet.uuid
        entity.currentItem = packet.currentItem
        entity.mergeMetadata(packet.metadata)
        entity.setPose(nanos, packet.x, packet.y, packet.z, packet.yaw, packet.pitch, packet.yaw)
    }

    private fun spawnObject(packet: SpawnObject, nanos: Long) {
        val entity = spawn(packet.entityId, EntityKind.OBJECT, nanos)
        entity.type = packet.type
        entity.objectData = packet.data
        entity.velocityX = packet.velocityX
        entity.velocityY = packet.velocityY
        entity.velocityZ = packet.velocityZ
        entity.setPose(nanos, packet.x, packet.y, packet.z, packet.yaw, packet.pitch)
    }

    private fun spawnMob(packet: SpawnMob, nanos: Long) {
        val entity = spawn(packet.entityId, EntityKind.MOB, nanos)
        entity.type = packet.type
        entity.velocityX = packet.velocityX
        entity.velocityY = packet.velocityY
        entity.velocityZ = packet.velocityZ
        entity.mergeMetadata(packet.metadata)
        entity.setPose(nanos, packet.x, packet.y, packet.z, packet.yaw, packet.pitch, packet.headPitch)
    }

    private fun destroy(id: Int) {
        entities.remove(id) ?: return
        entities.forEach { _, other ->
            if (other.vehicleId == id) other.vehicleId = -1
            if (other.leashHolderId == id) other.leashHolderId = -1
        }
        if (localPlayer.vehicleId == id) localPlayer.vehicleId = -1
    }

    private fun attach(packet: AttachEntity) {
        if (packet.entityId == localPlayer.entityId) {
            if (!packet.leash) localPlayer.vehicleId = packet.vehicleId
            return
        }
        val entity = entities[packet.entityId] ?: return
        if (packet.leash) entity.leashHolderId = packet.vehicleId else entity.vehicleId = packet.vehicleId
    }

    private fun positionAndLook(packet: PlayerPositionAndLook, nanos: Long) {
        val player = localPlayer
        val x = if (packet.isRelative(PlayerPositionAndLook.RELATIVE_X)) player.x + packet.x else packet.x
        val y = if (packet.isRelative(PlayerPositionAndLook.RELATIVE_Y)) player.y + packet.y else packet.y
        val z = if (packet.isRelative(PlayerPositionAndLook.RELATIVE_Z)) player.z + packet.z else packet.z
        val yaw = if (packet.isRelative(PlayerPositionAndLook.RELATIVE_YAW)) player.yaw + packet.yaw else packet.yaw
        val pitch =
            if (packet.isRelative(PlayerPositionAndLook.RELATIVE_PITCH)) player.pitch + packet.pitch else packet.pitch
        player.setPose(nanos, x, y, z, yaw, pitch)
    }

    private fun localMetadata(entries: List<MetadataEntry>) {
        for (entry in entries) {
            localPlayer.metadata.put(entry.index, entry)
            if (entry.index == 0 && entry.type == MetadataEntry.BYTE) {
                val flags = entry.byteValue()
                localPlayer.sneaking = flags and ShadowLocalPlayer.FLAG_SNEAKING != 0
                localPlayer.sprinting = flags and ShadowLocalPlayer.FLAG_SPRINTING != 0
            }
        }
    }

    private fun openWindow(packet: OpenWindow) {
        localPlayer.openWindowId = packet.windowId
        localPlayer.openWindowSize = 0
        localPlayer.window =
            ShadowWindow(packet.windowId, packet.type, packet.titleJson, packet.slotCount, packet.horseEntityId)
    }

    private fun closeWindow() {
        localPlayer.openWindowId = 0
        localPlayer.openWindowSize = 0
        localPlayer.window = null
    }

    private fun setSlot(packet: SetSlot) {
        if (packet.windowId == -1 && packet.slot == -1) {
            localPlayer.cursorItem = packet.item
            return
        }
        localPlayer.window?.takeIf { it.id == packet.windowId }?.setItem(packet.slot, packet.item)
        if (packet.windowId == 0) {
            if (packet.slot in localPlayer.inventory.indices) localPlayer.inventory[packet.slot] = packet.item
            return
        }
        if (packet.windowId != localPlayer.openWindowId) return
        val playerSlot = packet.slot - (localPlayer.openWindowSize - PLAYER_SLOTS_IN_CONTAINER)
        if (playerSlot in 0 until PLAYER_SLOTS_IN_CONTAINER) localPlayer.inventory[MAIN_INVENTORY_START + playerSlot] =
            packet.item
    }

    private fun windowItems(packet: WindowItems) {
        if (packet.windowId == 0) {
            for (index in packet.items.indices) if (index < localPlayer.inventory.size) localPlayer.inventory[index] =
                packet.items[index]
            return
        }
        localPlayer.window?.takeIf { it.id == packet.windowId }?.setItems(packet.items)
        if (packet.windowId != localPlayer.openWindowId) return
        localPlayer.openWindowSize = packet.items.size
        val offset = packet.items.size - PLAYER_SLOTS_IN_CONTAINER
        if (offset < 0) return
        for (index in 0 until PLAYER_SLOTS_IN_CONTAINER) localPlayer.inventory[MAIN_INVENTORY_START + index] =
            packet.items[offset + index]
    }

    private companion object {
        const val PLAYER_SLOTS_IN_CONTAINER = 36
        const val MAIN_INVENTORY_START = 9
    }
}
