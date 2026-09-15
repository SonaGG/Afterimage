package gg.sona.afterimage.protocol47.diff

import gg.sona.afterimage.world.*
import gg.sona.afterimage.core.collect.ChunkKeys
import gg.sona.afterimage.net.PackedPosition
import gg.sona.afterimage.net.PacketWriter
import gg.sona.afterimage.protocol.*
import gg.sona.afterimage.protocol47.ContentHash
import gg.sona.afterimage.protocol47.shadow.*

object StateDiff {
    private const val MAX_BULK_BYTES = 1 shl 20
    private const val MAX_BULK_CHUNKS = 10

    fun canDiff(from: ShadowClient, to: ShadowClient): Boolean =
        from.joined && to.joined && from.level.dimension == to.level.dimension && from.localPlayer.entityId == to.localPlayer.entityId

    fun packets(from: ShadowClient, to: ShadowClient, nanos: Long): List<PlayPacket> {
        val out = ArrayList<ClientboundPacket>(64)
        if (from.resourcePack != to.resourcePack) to.resourcePack?.let { out += ResourcePackSend(it.url, it.hash) }
        diffPlayerList(from, to, out)
        diffWorldMeta(from, to, nanos, out)
        diffChunks(from, to, out)
        diffEntities(from, to, nanos, out)
        diffScoreboard(from, to, out)
        diffLocalPlayer(from, to, nanos, out)
        diffMaps(from, to, out)
        diffWindow(from, to, out)
        val result = ArrayList<PlayPacket>(out.size + 2)
        diffOverlays(to, nanos, result)
        result += out
        val target = to.localPlayer
        if (target.hasPosition) result += LocalPose(target.x, target.y, target.z, target.yaw, target.pitch)
        target.cameraFrames.before(nanos)?.let { result += CameraFrame(it.modelView, it.fov, it.position, it.hand) }
        if (from.localPlayer.screen != target.screen) result += target.screen
        if (from.localPlayer.target != target.target) result += target.target ?: LocalTarget.NONE
        return result
    }

    private fun diffPlayerList(from: ShadowClient, to: ShadowClient, out: MutableList<ClientboundPacket>) {
        val removed = from.players.entries.keys.filter { it !in to.players.entries }
        if (removed.isNotEmpty()) out += PlayerListItem(
            PlayerListItem.REMOVE_PLAYER,
            removed.map { SnapshotEncoder.profileEntry(from, it) })
        val added = to.players.entries.values.filter { it.uuid !in from.players.entries }
        if (added.isNotEmpty()) out += PlayerListItem(PlayerListItem.ADD_PLAYER, added.map { it.toPacketEntry() })
        for (entry in to.players.entries.values) {
            val previous = from.players.entries[entry.uuid] ?: continue
            if (previous.gameMode != entry.gameMode) out += PlayerListItem(
                PlayerListItem.UPDATE_GAME_MODE,
                listOf(entry.toPacketEntry())
            )
            if (previous.ping != entry.ping) out += PlayerListItem(
                PlayerListItem.UPDATE_LATENCY,
                listOf(entry.toPacketEntry())
            )
            if (previous.displayNameJson != entry.displayNameJson) out += PlayerListItem(
                PlayerListItem.UPDATE_DISPLAY_NAME,
                listOf(entry.toPacketEntry())
            )
        }
        if (from.players.headerJson != to.players.headerJson || from.players.footerJson != to.players.footerJson) {
            if (to.players.headerJson != null || to.players.footerJson != null) {
                out += PlayerListHeaderFooter(to.players.headerJson ?: EMPTY_TEXT, to.players.footerJson ?: EMPTY_TEXT)
            }
        }
    }

    private fun diffWorldMeta(from: ShadowClient, to: ShadowClient, nanos: Long, out: MutableList<ClientboundPacket>) {
        val source = from.level
        val target = to.level
        if (target.hasTime) out += TimeUpdate(target.worldAge, SnapshotEncoder.currentTimeOfDay(target, nanos))
        if (target.hasSpawn && (!source.hasSpawn || source.spawnPosition != target.spawnPosition)) out += SpawnPosition(
            target.spawnPosition
        )
        if (source.difficulty != target.difficulty) out += ServerDifficulty(target.difficulty)
        if (target.border.initialized && !sameBorder(source.border, target.border)) out += target.border.toInitialize(
            nanos
        )
        if (source.raining != target.raining) out += ChangeGameState(
            if (target.raining) ChangeGameState.BEGIN_RAINING else ChangeGameState.END_RAINING,
            0f
        )
        if (target.raining) {
            if (!source.raining || source.rainStrength != target.rainStrength) out += ChangeGameState(
                ChangeGameState.RAIN_STRENGTH,
                target.rainStrength
            )
            if (!source.raining || source.thunderStrength != target.thunderStrength) out += ChangeGameState(
                ChangeGameState.THUNDER_STRENGTH,
                target.thunderStrength
            )
        }
    }

    private fun sameBorder(a: BorderState, b: BorderState): Boolean =
        a.initialized == b.initialized && a.centerX == b.centerX && a.centerZ == b.centerZ && a.oldRadius == b.oldRadius && a.newRadius == b.newRadius &&
                a.lerpSpeed == b.lerpSpeed && a.lerpStartedAtNanos == b.lerpStartedAtNanos && a.warningTime == b.warningTime && a.warningBlocks == b.warningBlocks

    private fun diffChunks(from: ShadowClient, to: ShadowClient, out: MutableList<ClientboundPacket>) {
        val source = from.level
        val target = to.level
        val skyLight = target.hasSkyLight
        for (key in source.chunks.keys()) {
            if (target.chunks[key] == null) out += ChunkData(
                ChunkKeys.x(key),
                ChunkKeys.z(key),
                true,
                0,
                ByteArray(ChunkLayout.BIOME_BYTES)
            )
        }
        val sourceBlockEntities = blockEntitiesByChunk(source)
        val targetBlockEntities = blockEntitiesByChunk(target)
        val changed = ArrayList<ShadowChunk>()
        target.chunks.forEach { key, chunk ->
            val previous = source.chunks[key]
            val same = previous != null &&
                    previous.contentHash() == chunk.contentHash() &&
                    sourceBlockEntities[key] == targetBlockEntities[key]
            if (!same) changed += chunk
        }
        changed.sortWith(compareBy({ it.chunkX }, { it.chunkZ }))
        var batch = ArrayList<BulkChunk>()
        var batchBytes = 0
        for (chunk in changed) {
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
        for (chunk in changed) {
            val key = ChunkKeys.of(chunk.chunkX, chunk.chunkZ)
            target.blockEntities.forEach { position, record ->
                if (chunkKeyOf(position) == key) out += UpdateBlockEntity(
                    record.position,
                    record.action,
                    record.nbt
                )
            }
            target.signs.forEach { position, sign ->
                if (chunkKeyOf(position) == key) out += UpdateSign(
                    sign.position,
                    sign.lines
                )
            }
        }
    }

    private fun blockEntitiesByChunk(world: ShadowWorld): Map<Long, Long> {
        if (world.blockEntities.isEmpty() && world.signs.isEmpty()) return emptyMap()
        val grouped = HashMap<Long, MutableList<ByteArray>>()
        world.blockEntities.forEach { position, record ->
            grouped.getOrPut(chunkKeyOf(position)) { ArrayList() } += PacketCodec.encode(
                UpdateBlockEntity(
                    record.position,
                    record.action,
                    record.nbt
                ), 0L
            ).payload
        }
        world.signs.forEach { position, sign ->
            grouped.getOrPut(chunkKeyOf(position)) { ArrayList() } += PacketCodec.encode(
                UpdateSign(
                    sign.position,
                    sign.lines
                ), 0L
            ).payload
        }
        val hashes = HashMap<Long, Long>(grouped.size)
        for ((key, payloads) in grouped) {
            payloads.sortWith(ByteArrayComparator)
            var hash = ContentHash.seed()
            for (payload in payloads) hash = ContentHash.mix(hash, payload)
            hashes[key] = hash
        }
        return hashes
    }

    private object ByteArrayComparator : Comparator<ByteArray> {
        override fun compare(a: ByteArray, b: ByteArray): Int {
            val length = minOf(a.size, b.size)
            for (index in 0 until length) {
                val difference = (a[index].toInt() and 0xFF) - (b[index].toInt() and 0xFF)
                if (difference != 0) return difference
            }
            return a.size - b.size
        }
    }

    private fun chunkKeyOf(position: Long): Long =
        ChunkKeys.of(PackedPosition.x(position) shr 4, PackedPosition.z(position) shr 4)

    private fun diffEntities(from: ShadowClient, to: ShadowClient, nanos: Long, out: MutableList<ClientboundPacket>) {
        val removed = ArrayList<Int>()
        from.entities.forEach { id, previous ->
            if (to.entities[id]?.visibleAt(nanos) != true || !previous.visibleAt(from.lastNanos)) removed += id
        }
        val respawned = ArrayList<ShadowEntity>()
        val updated = ArrayList<Pair<ShadowEntity, ShadowEntity>>()
        to.entities.forEach { id, entity ->
            if (!entity.visibleAt(nanos)) return@forEach
            val previous = from.entities[id]?.takeIf { it.visibleAt(from.lastNanos) }
            when {
                previous == null -> respawned += entity
                previous.kind != entity.kind || previous.type != entity.type || previous.uuid != entity.uuid || (previous.dead && !entity.dead) -> {
                    removed += id
                    respawned += entity
                }

                else -> updated += previous to entity
            }
        }
        if (removed.isNotEmpty()) out += DestroyEntities(removed.toIntArray())
        respawned.sortBy { it.id }
        for (entity in respawned) SnapshotEncoder.entityPackets(to, entity, nanos, out)
        for ((previous, entity) in updated) updateEntity(previous, entity, nanos, out)
        for (entity in respawned) SnapshotEncoder.attachmentPackets(entity, out)
        for ((previous, entity) in updated) {
            if (previous.vehicleId != entity.vehicleId) out += AttachEntity(entity.id, entity.vehicleId, false)
            if (previous.leashHolderId != entity.leashHolderId) out += AttachEntity(
                entity.id,
                entity.leashHolderId,
                true
            )
        }
    }

    private fun updateEntity(
        previous: ShadowEntity,
        entity: ShadowEntity,
        nanos: Long,
        out: MutableList<ClientboundPacket>
    ) {
        if (entity.dead) {
            if (!previous.dead) out += EntityStatus(entity.id, EntityStatus.DEAD)
            return
        }
        if (entity.kind != EntityKind.PAINTING) {
            out += EntityTeleport(
                entity.id,
                entity.fixedX,
                entity.fixedY,
                entity.fixedZ,
                entity.yaw,
                entity.pitch,
                entity.onGround
            )
            if (entity.kind == EntityKind.PLAYER || entity.kind == EntityKind.MOB) out += EntityHeadLook(
                entity.id,
                entity.headYaw
            )
        }
        if (!entity.metadata.isEmpty() && !sameMetadata(previous, entity)) out += EntityMetadata(
            entity.id,
            entity.metadata.values().sortedBy { it.index })
        for (slot in 0 until 5) {
            val item = entity.equipment[slot]
            val previousItem = previous.equipment[slot]
            if (!sameItem(previousItem, item)) out += EntityEquipment(entity.id, slot, item ?: ItemStack.EMPTY)
        }
        previous.effects.forEach { id, _ -> if (entity.effects[id] == null) out += RemoveEntityEffect(entity.id, id) }
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
        if (entity.attributes.isNotEmpty() && previous.attributes != entity.attributes) out += EntityProperties(
            entity.id,
            entity.attributes.values.toList()
        )
        val nbt = entity.nbt
        if (nbt != null && nbt !== previous.nbt) out += UpdateEntityNbt(entity.id, nbt)
    }

    private fun sameMetadata(a: ShadowEntity, b: ShadowEntity): Boolean {
        if (a.metadata.size != b.metadata.size) return false
        var same = true
        b.metadata.forEach { index, entry ->
            val other = a.metadata[index]
            if (other == null || other.type != entry.type || !sameValue(other.value, entry.value)) same = false
        }
        return same
    }

    private fun sameValue(a: Any, b: Any): Boolean = when {
        a is ItemStack && b is ItemStack -> sameItem(a, b)
        else -> a == b
    }

    fun sameItem(a: ItemStack?, b: ItemStack?): Boolean {
        val left = a ?: ItemStack.EMPTY
        val right = b ?: ItemStack.EMPTY
        if (left.isEmpty && right.isEmpty) return true
        if (left.id != right.id || left.count != right.count || left.damage != right.damage) return false
        if (left.tag === right.tag) return true
        if (left.tag == null || right.tag == null) return false
        val first = PacketWriter(64).also { SlotCodec.write(it, left) }
        val second = PacketWriter(64).also { SlotCodec.write(it, right) }
        return first.toByteArray().contentEquals(second.toByteArray())
    }

    private fun diffScoreboard(from: ShadowClient, to: ShadowClient, out: MutableList<ClientboundPacket>) {
        val source = from.scoreboard
        val target = to.scoreboard
        val removedObjectives = source.objectives.keys.filter { target.objectives[it] == null }.toSet()
        for (name in removedObjectives) out += ScoreboardObjective(name, ScoreboardObjective.REMOVE, null, null)
        for (objective in target.objectives.values) {
            val previous = source.objectives[objective.name]
            if (previous == null) {
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
                continue
            }
            if (previous.displayName != objective.displayName || previous.type != objective.type) {
                out += ScoreboardObjective(
                    objective.name,
                    ScoreboardObjective.UPDATE,
                    objective.displayName,
                    objective.type
                )
            }
            for (entry in previous.scores.keys) if (!objective.scores.containsKey(entry)) out += UpdateScore(
                entry,
                UpdateScore.REMOVE,
                objective.name,
                0
            )
            for ((entry, value) in objective.scores) if (previous.scores[entry] != value) out += UpdateScore(
                entry,
                UpdateScore.CHANGE,
                objective.name,
                value
            )
        }
        for (slot in target.displaySlots.indices) {
            val name = target.displaySlots[slot]
            val previous = source.displaySlots[slot]
            if (previous != name || (name != null && name in removedObjectives)) out += DisplayScoreboard(
                slot,
                name ?: ""
            )
        }
        val targetTeamOf = HashMap<String, String>()
        for (team in target.teams.values) for (member in team.members) targetTeamOf[member] = team.name
        val sourceTeamOf = HashMap<String, String>()
        for (team in source.teams.values) for (member in team.members) sourceTeamOf[member] = team.name
        for (name in source.teams.keys) if (target.teams[name] == null) out += Teams(
            name,
            Teams.REMOVE,
            null,
            null,
            null,
            0,
            null,
            -1,
            emptyList()
        )
        for (previous in source.teams.values) {
            val team = target.teams[previous.name] ?: continue
            val gone = previous.members.filter { it !in team.members && targetTeamOf[it] == null }
            if (gone.isNotEmpty()) out += team.toPacket(Teams.REMOVE_PLAYERS, gone)
        }
        for (team in target.teams.values) {
            val previous = source.teams[team.name]
            if (previous == null) {
                out += team.toPacket(Teams.CREATE)
                continue
            }
            if (previous.displayName != team.displayName || previous.prefix != team.prefix || previous.suffix != team.suffix ||
                previous.friendlyFire != team.friendlyFire || previous.nameTagVisibility != team.nameTagVisibility || previous.color != team.color
            ) {
                out += team.toPacket(Teams.UPDATE, emptyList())
            }
            val added = team.members.filter { sourceTeamOf[it] != team.name }
            if (added.isNotEmpty()) out += team.toPacket(Teams.ADD_PLAYERS, added)
        }
    }

    private fun diffLocalPlayer(
        from: ShadowClient,
        to: ShadowClient,
        nanos: Long,
        out: MutableList<ClientboundPacket>
    ) {
        val source = from.localPlayer
        val target = to.localPlayer
        if (source.gameMode != target.gameMode) out += ChangeGameState(
            ChangeGameState.CHANGE_GAME_MODE,
            target.gameMode.toFloat()
        )
        if (target.hasPosition) out += PlayerPositionAndLook(target.x, target.y, target.z, target.yaw, target.pitch, 0)
        if (source.health != target.health || source.food != target.food || source.saturation != target.saturation) out += UpdateHealth(
            target.health,
            target.food,
            target.saturation
        )
        if (source.xpBar != target.xpBar || source.xpLevel != target.xpLevel || source.xpTotal != target.xpTotal) out += SetExperience(
            target.xpBar,
            target.xpLevel,
            target.xpTotal
        )
        if (source.abilityFlags != target.abilityFlags || source.flyingSpeed != target.flyingSpeed || source.walkingSpeed != target.walkingSpeed) {
            out += PlayerAbilities(target.abilityFlags, target.flyingSpeed, target.walkingSpeed)
        }
        var inventoryChanged = false
        for (slot in target.inventory.indices) if (!sameItem(
                source.inventory[slot],
                target.inventory[slot]
            )
        ) inventoryChanged = true
        if (inventoryChanged) out += WindowItems(0, target.inventory.toList())
        if (source.heldSlot != target.heldSlot || inventoryChanged) out += HeldItemChange(target.heldSlot)
        val metadata = target.entityMetadata()
        if (metadata != source.entityMetadata() || source.sneaking != target.sneaking || source.sprinting != target.sprinting) out += EntityMetadata(
            target.entityId,
            metadata
        )
        source.effects.forEach { id, _ ->
            if (target.effects[id] == null) out += RemoveEntityEffect(
                target.entityId,
                id
            )
        }
        target.effects.forEach { _, effect ->
            val remaining = effect.remainingTicks(nanos)
            if (remaining > 0) out += EntityEffect(
                target.entityId,
                effect.effectId,
                effect.amplifier,
                remaining,
                effect.hideParticles
            )
        }
        if (target.attributes.isNotEmpty() && source.attributes != target.attributes) out += EntityProperties(
            target.entityId,
            target.attributes.values.toList()
        )
        if (source.vehicleId != target.vehicleId) out += AttachEntity(target.entityId, target.vehicleId, false)
        if (source.cameraEntityId != target.cameraEntityId) out += Camera(if (target.cameraEntityId == -1) target.entityId else target.cameraEntityId)
    }

    private fun diffWindow(from: ShadowClient, to: ShadowClient, out: MutableList<ClientboundPacket>) {
        val source = from.localPlayer.window
        val target = to.localPlayer.window
        if (target == null) {
            if (source != null) out += CloseWindow(source.id)
            return
        }
        if (source == null || !source.sameIdentity(target)) {
            if (source != null) out += CloseWindow(source.id)
            SnapshotEncoder.encodeWindow(to, out)
            return
        }
        if (source.sameContents(target)) return
        if (target.items.isNotEmpty()) out += WindowItems(target.id, target.items.toList())
        for ((property, value) in target.properties) if (source.properties[property] != value) out += WindowProperty(
            target.id,
            property,
            value
        )
        val trades = target.trades
        if (trades != null && !source.trades.contentEquals(trades)) out += PluginMessage("MC|TrList", trades)
    }

    private fun diffMaps(from: ShadowClient, to: ShadowClient, out: MutableList<ClientboundPacket>) {
        for (canvas in to.level.maps.values) {
            if (!canvas.hasPixels) continue
            val previous = from.level.maps[canvas.mapId]
            if (previous != null && previous.hasPixels && previous.pixels.contentEquals(canvas.pixels) && previous.scale == canvas.scale && previous.icons == canvas.icons) continue
            out += MapData(canvas.mapId, canvas.scale, canvas.icons, 128, 128, 0, 0, canvas.pixels.copyOf())
        }
    }

    private const val EMPTY_TEXT = "{\"text\":\"\"}"
}

private fun diffOverlays(to: ShadowClient, toNanos: Long, out: MutableList<PlayPacket>) {
    out += to.overlays.snapshot(toNanos)
}
