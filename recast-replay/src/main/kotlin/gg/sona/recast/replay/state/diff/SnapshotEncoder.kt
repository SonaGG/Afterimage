package gg.sona.recast.replay.state.diff

import gg.sona.recast.net.CapturedPacket
import gg.sona.recast.protocol.*
import gg.sona.recast.replay.state.shadow.EntityKind
import gg.sona.recast.replay.state.shadow.ShadowClient
import gg.sona.recast.replay.state.shadow.ShadowEntity
import gg.sona.recast.replay.state.shadow.ShadowWorld
import java.util.*

object SnapshotEncoder {
    private const val MAX_BULK_BYTES = 1 shl 20
    private const val MAX_BULK_CHUNKS = 10

    fun encode(client: ShadowClient, nanos: Long): List<CapturedPacket> =
        PacketCodec.encodeAll(packets(client, nanos), nanos)

    fun packets(client: ShadowClient, nanos: Long): List<PlayPacket> {
        if (!client.joined) return emptyList()
        val out = ArrayList<ClientboundPacket>(256)
        val world = client.world
        val player = client.localPlayer

        out += JoinGame(
            player.entityId,
            player.gameMode,
            world.dimension,
            world.difficulty,
            world.maxPlayers,
            world.levelType,
            player.reducedDebugInfo
        )
        out += ServerDifficulty(world.difficulty)
        client.resourcePack?.let { out += ResourcePackSend(it.url, it.hash) }
        out += PlayerAbilities(player.abilityFlags, player.flyingSpeed, player.walkingSpeed)
        out += HeldItemChange(player.heldSlot)
        if (world.hasSpawn) out += SpawnPosition(world.spawnPosition)
        if (world.hasTime) out += TimeUpdate(world.worldAge, currentTimeOfDay(world, nanos))
        if (world.border.initialized) out += world.border.toInitialize(nanos)
        if (world.raining) {
            out += ChangeGameState(ChangeGameState.BEGIN_RAINING, 0f)
            out += ChangeGameState(ChangeGameState.RAIN_STRENGTH, world.rainStrength)
            out += ChangeGameState(ChangeGameState.THUNDER_STRENGTH, world.thunderStrength)
        }
        encodePlayerList(client, out)
        if (player.hasPosition) out += PlayerPositionAndLook(player.x, player.y, player.z, player.yaw, player.pitch, 0)
        encodeScoreboard(client, out)
        encodeChunks(client, out)
        encodeEntities(client, nanos, out)
        encodeLocalPlayer(client, nanos, out)
        encodeMaps(client, out)
        encodeWindow(client, out)
        val result = ArrayList<PlayPacket>(out.size + 1)
        result += out
        if (player.screen.isOpen) result += player.screen
        player.target?.let { result += it }
        result += client.overlays.snapshot(nanos)
        return result
    }

    fun currentTimeOfDay(world: ShadowWorld, nanos: Long): Long {
        if (world.timeOfDay < 0) return world.timeOfDay
        val elapsedTicks = (nanos - world.timeUpdatedAtNanos) / 50_000_000L
        return world.timeOfDay + elapsedTicks.coerceAtLeast(0L)
    }

    private fun encodePlayerList(client: ShadowClient, out: MutableList<ClientboundPacket>) {
        val players = client.players
        val entries = players.entries.values.map { it.toPacketEntry() }
        if (entries.isNotEmpty()) out += PlayerListItem(PlayerListItem.ADD_PLAYER, entries)
        val header = players.headerJson
        val footer = players.footerJson
        if (header != null || footer != null) out += PlayerListHeaderFooter(
            header ?: "{\"text\":\"\"}",
            footer ?: "{\"text\":\"\"}"
        )
    }

    private fun encodeScoreboard(client: ShadowClient, out: MutableList<ClientboundPacket>) {
        val scoreboard = client.scoreboard
        for (objective in scoreboard.objectives.values) {
            out += ScoreboardObjective(
                objective.name,
                ScoreboardObjective.CREATE,
                objective.displayName,
                objective.type
            )
            for ((entry, value) in objective.scores) out += UpdateScore(
                entry,
                UpdateScore.CHANGE,
                objective.name,
                value
            )
        }
        for (slot in scoreboard.displaySlots.indices) {
            val name = scoreboard.displaySlots[slot] ?: continue
            out += DisplayScoreboard(slot, name)
        }
        for (team in scoreboard.teams.values) out += team.toPacket(Teams.CREATE)
    }

    private fun encodeChunks(client: ShadowClient, out: MutableList<ClientboundPacket>) {
        val world = client.world
        val skyLight = world.hasSkyLight
        val chunks = world.chunks.values().sortedWith(compareBy({ it.chunkX }, { it.chunkZ }))
        var batch = ArrayList<BulkChunk>()
        var batchBytes = 0
        for (chunk in chunks) {
            val size = chunk.encodedSize(skyLight)
            if (batch.isNotEmpty() && (batchBytes + size > MAX_BULK_BYTES || batch.size >= MAX_BULK_CHUNKS)) {
                out += MapChunkBulk(skyLight, batch)
                batch = ArrayList()
                batchBytes = 0
            }
            batch += chunk.encodeBulk(skyLight)
            batchBytes += size
        }
        if (batch.isNotEmpty()) out += MapChunkBulk(skyLight, batch)
        world.blockEntities.forEach { _, record ->
            out += UpdateBlockEntity(
                record.position,
                record.action,
                record.nbt
            )
        }
        world.signs.forEach { _, sign -> out += UpdateSign(sign.position, sign.lines) }
    }

    private fun encodeEntities(client: ShadowClient, nanos: Long, out: MutableList<ClientboundPacket>) {
        val entities = client.entities.values().sortedBy { it.id }
        for (entity in entities) entityPackets(client, entity, nanos, out)
        for (entity in entities) attachmentPackets(entity, out)
        val player = client.localPlayer
        if (player.vehicleId != -1) out += AttachEntity(player.entityId, player.vehicleId, false)
    }

    fun attachmentPackets(entity: ShadowEntity, out: MutableList<ClientboundPacket>) {
        if (entity.vehicleId != -1) out += AttachEntity(entity.id, entity.vehicleId, false)
        if (entity.leashHolderId != -1) out += AttachEntity(entity.id, entity.leashHolderId, true)
    }

    fun entityPackets(client: ShadowClient, entity: ShadowEntity, nanos: Long, out: MutableList<ClientboundPacket>) {
        run {
            when (entity.kind) {
                EntityKind.PLAYER -> {
                    val uuid = entity.uuid ?: return@run
                    ensureProfile(client, uuid, out)
                    out += SpawnPlayer(
                        entity.id,
                        uuid,
                        entity.fixedX,
                        entity.fixedY,
                        entity.fixedZ,
                        entity.yaw,
                        entity.pitch,
                        entity.currentItem,
                        entity.metadata.values().sortedBy { it.index })
                    if (client.players.entries[uuid] == null) out += PlayerListItem(
                        PlayerListItem.REMOVE_PLAYER,
                        listOf(profileEntry(client, uuid))
                    )
                }

                EntityKind.MOB -> out += SpawnMob(
                    entity.id,
                    entity.type,
                    entity.fixedX,
                    entity.fixedY,
                    entity.fixedZ,
                    entity.yaw,
                    entity.pitch,
                    entity.headYaw,
                    entity.velocityX,
                    entity.velocityY,
                    entity.velocityZ,
                    entity.metadata.values().sortedBy { it.index })

                EntityKind.OBJECT -> {
                    out += SpawnObject(
                        entity.id,
                        entity.type,
                        entity.fixedX,
                        entity.fixedY,
                        entity.fixedZ,
                        entity.pitch,
                        entity.yaw,
                        entity.objectData,
                        entity.velocityX,
                        entity.velocityY,
                        entity.velocityZ
                    )
                    if (!entity.metadata.isEmpty()) out += EntityMetadata(
                        entity.id,
                        entity.metadata.values().sortedBy { it.index })
                }

                EntityKind.PAINTING -> out += SpawnPainting(
                    entity.id,
                    entity.paintingTitle,
                    entity.paintingPosition,
                    entity.paintingFacing
                )

                EntityKind.EXPERIENCE_ORB -> out += SpawnExperienceOrb(
                    entity.id,
                    entity.fixedX,
                    entity.fixedY,
                    entity.fixedZ,
                    entity.orbCount
                )

                EntityKind.GLOBAL -> out += SpawnGlobalEntity(
                    entity.id,
                    entity.type,
                    entity.fixedX,
                    entity.fixedY,
                    entity.fixedZ
                )
            }
            if (entity.kind == EntityKind.PLAYER || entity.kind == EntityKind.MOB) {
                out += EntityHeadLook(entity.id, entity.headYaw)
                for (slot in 0 until 5) {
                    val item = entity.equipment[slot] ?: continue
                    if (!item.isEmpty) out += EntityEquipment(entity.id, slot, item)
                }
                if (entity.attributes.isNotEmpty()) out += EntityProperties(
                    entity.id,
                    entity.attributes.values.toList()
                )
            }
            entity.effects.forEach { _, effect ->
                val remaining = effect.remainingTicks(nanos)
                if (remaining > 0) out += EntityEffect(
                    entity.id,
                    effect.effectId,
                    effect.amplifier,
                    remaining,
                    effect.hideParticles
                )
            }
            entity.nbt?.let { out += UpdateEntityNbt(entity.id, it) }
            if (entity.dead) out += EntityStatus(entity.id, EntityStatus.DEAD)
        }
    }

    fun ensureProfile(client: ShadowClient, uuid: UUID, out: MutableList<ClientboundPacket>) {
        if (client.players.entries.containsKey(uuid)) return
        out += PlayerListItem(PlayerListItem.ADD_PLAYER, listOf(profileEntry(client, uuid)))
    }

    fun profileEntry(client: ShadowClient, uuid: UUID) =
        client.players.profile(uuid)?.toPacketEntry() ?: PlayerListEntry(
            uuid,
            uuid.toString().take(16),
            emptyList(),
            0,
            0,
            null
        )

    private fun encodeLocalPlayer(client: ShadowClient, nanos: Long, out: MutableList<ClientboundPacket>) {
        val player = client.localPlayer
        out += UpdateHealth(player.health, player.food, player.saturation)
        out += SetExperience(player.xpBar, player.xpLevel, player.xpTotal)
        out += WindowItems(0, player.inventory.toList())
        out += EntityMetadata(player.entityId, player.entityMetadata())
        if (player.attributes.isNotEmpty()) out += EntityProperties(player.entityId, player.attributes.values.toList())
        player.effects.forEach { _, effect ->
            val remaining = effect.remainingTicks(nanos)
            if (remaining > 0) out += EntityEffect(
                player.entityId,
                effect.effectId,
                effect.amplifier,
                remaining,
                effect.hideParticles
            )
        }
        for (slot in 1..4) {
            val item = player.equipmentItem(slot)
            if (!item.isEmpty) out += EntityEquipment(player.entityId, slot, item)
        }
        val held = player.heldItem
        if (!held.isEmpty) out += EntityEquipment(player.entityId, 0, held)
        if (player.cameraEntityId != -1) out += Camera(player.cameraEntityId)
    }

    fun encodeWindow(client: ShadowClient, out: MutableList<ClientboundPacket>) {
        val window = client.localPlayer.window ?: return
        out += OpenWindow(window.id, window.type, window.titleJson, window.size, window.horseEntityId)
        if (window.items.isNotEmpty()) out += WindowItems(window.id, window.items.toList())
        for ((property, value) in window.properties) out += WindowProperty(window.id, property, value)
        window.trades?.let { out += PluginMessage("MC|TrList", it) }
    }

    private fun encodeMaps(client: ShadowClient, out: MutableList<ClientboundPacket>) {
        for (canvas in client.world.maps.values) {
            if (!canvas.hasPixels) continue
            out += MapData(canvas.mapId, canvas.scale, canvas.icons, 128, 128, 0, 0, canvas.pixels.copyOf())
        }
    }
}
