package gg.sona.afterimage.protocol

import gg.sona.afterimage.net.PacketReader
import gg.sona.afterimage.net.PacketWriter

object ClientboundCodec {

    fun decode(packetId: Int, reader: PacketReader): ClientboundPacket? = when (packetId) {
        ClientboundPlay.JOIN_GAME -> JoinGame(
            reader.readInt(),
            reader.readUnsignedByte(),
            reader.readByte().toInt(),
            reader.readUnsignedByte(),
            reader.readUnsignedByte(),
            reader.readString(16),
            reader.readBoolean(),
        )

        ClientboundPlay.CHAT_MESSAGE -> ChatMessage(reader.readString(), reader.readByte().toInt())
        ClientboundPlay.TIME_UPDATE -> TimeUpdate(reader.readLong(), reader.readLong())
        ClientboundPlay.ENTITY_EQUIPMENT -> EntityEquipment(
            reader.readVarInt(),
            reader.readShort().toInt(),
            SlotCodec.read(reader)
        )

        ClientboundPlay.SPAWN_POSITION -> SpawnPosition(reader.readPosition())
        ClientboundPlay.UPDATE_HEALTH -> UpdateHealth(reader.readFloat(), reader.readVarInt(), reader.readFloat())
        ClientboundPlay.RESPAWN -> Respawn(
            reader.readInt(),
            reader.readUnsignedByte(),
            reader.readUnsignedByte(),
            reader.readString(16)
        )

        ClientboundPlay.PLAYER_POSITION_AND_LOOK -> PlayerPositionAndLook(
            reader.readDouble(),
            reader.readDouble(),
            reader.readDouble(),
            reader.readFloat(),
            reader.readFloat(),
            reader.readUnsignedByte(),
        )

        ClientboundPlay.HELD_ITEM_CHANGE -> HeldItemChange(reader.readByte().toInt())
        ClientboundPlay.USE_BED -> UseBed(reader.readVarInt(), reader.readPosition())
        ClientboundPlay.ANIMATION -> Animation(reader.readVarInt(), reader.readUnsignedByte())
        ClientboundPlay.SPAWN_PLAYER -> SpawnPlayer(
            reader.readVarInt(),
            reader.readUuid(),
            reader.readInt(),
            reader.readInt(),
            reader.readInt(),
            reader.readByte(),
            reader.readByte(),
            reader.readShort().toInt(),
            MetadataCodec.read(reader),
        )

        ClientboundPlay.COLLECT_ITEM -> CollectItem(reader.readVarInt(), reader.readVarInt())
        ClientboundPlay.SPAWN_OBJECT -> readSpawnObject(reader)
        ClientboundPlay.SPAWN_MOB -> SpawnMob(
            reader.readVarInt(),
            reader.readUnsignedByte(),
            reader.readInt(),
            reader.readInt(),
            reader.readInt(),
            reader.readByte(),
            reader.readByte(),
            reader.readByte(),
            reader.readShort().toInt(),
            reader.readShort().toInt(),
            reader.readShort().toInt(),
            MetadataCodec.read(reader),
        )

        ClientboundPlay.SPAWN_PAINTING -> SpawnPainting(
            reader.readVarInt(),
            reader.readString(13),
            reader.readPosition(),
            reader.readUnsignedByte()
        )

        ClientboundPlay.SPAWN_EXPERIENCE_ORB -> SpawnExperienceOrb(
            reader.readVarInt(),
            reader.readInt(),
            reader.readInt(),
            reader.readInt(),
            reader.readShort().toInt()
        )

        ClientboundPlay.ENTITY_VELOCITY -> EntityVelocity(
            reader.readVarInt(),
            reader.readShort().toInt(),
            reader.readShort().toInt(),
            reader.readShort().toInt()
        )

        ClientboundPlay.DESTROY_ENTITIES -> DestroyEntities(IntArray(reader.readVarInt()) { reader.readVarInt() })
        ClientboundPlay.ENTITY -> EntityIdle(reader.readVarInt())
        ClientboundPlay.ENTITY_RELATIVE_MOVE -> EntityRelativeMove(
            reader.readVarInt(),
            reader.readByte(),
            reader.readByte(),
            reader.readByte(),
            reader.readBoolean()
        )

        ClientboundPlay.ENTITY_LOOK -> EntityLook(
            reader.readVarInt(),
            reader.readByte(),
            reader.readByte(),
            reader.readBoolean()
        )

        ClientboundPlay.ENTITY_LOOK_AND_RELATIVE_MOVE -> EntityLookAndRelativeMove(
            reader.readVarInt(),
            reader.readByte(),
            reader.readByte(),
            reader.readByte(),
            reader.readByte(),
            reader.readByte(),
            reader.readBoolean(),
        )

        ClientboundPlay.ENTITY_TELEPORT -> EntityTeleport(
            reader.readVarInt(),
            reader.readInt(),
            reader.readInt(),
            reader.readInt(),
            reader.readByte(),
            reader.readByte(),
            reader.readBoolean(),
        )

        ClientboundPlay.ENTITY_HEAD_LOOK -> EntityHeadLook(reader.readVarInt(), reader.readByte())
        ClientboundPlay.ENTITY_STATUS -> EntityStatus(reader.readInt(), reader.readByte().toInt())
        ClientboundPlay.ATTACH_ENTITY -> AttachEntity(reader.readInt(), reader.readInt(), reader.readBoolean())
        ClientboundPlay.ENTITY_METADATA -> EntityMetadata(reader.readVarInt(), MetadataCodec.read(reader))
        ClientboundPlay.ENTITY_EFFECT -> EntityEffect(
            reader.readVarInt(),
            reader.readByte().toInt(),
            reader.readByte().toInt(),
            reader.readVarInt(),
            reader.readBoolean()
        )

        ClientboundPlay.REMOVE_ENTITY_EFFECT -> RemoveEntityEffect(reader.readVarInt(), reader.readByte().toInt())
        ClientboundPlay.SET_EXPERIENCE -> SetExperience(reader.readFloat(), reader.readVarInt(), reader.readVarInt())
        ClientboundPlay.ENTITY_PROPERTIES -> readEntityProperties(reader)
        ClientboundPlay.CHUNK_DATA -> {
            val chunkX = reader.readInt()
            val chunkZ = reader.readInt()
            val groundUp = reader.readBoolean()
            val mask = reader.readUnsignedShort()
            ChunkData(chunkX, chunkZ, groundUp, mask, reader.readBytes(reader.readVarInt()))
        }

        ClientboundPlay.MULTI_BLOCK_CHANGE -> {
            val chunkX = reader.readInt()
            val chunkZ = reader.readInt()
            val count = reader.readVarInt()
            val records = ArrayList<BlockChangeRecord>(count)
            repeat(count) {
                val horizontal = reader.readUnsignedByte()
                val y = reader.readUnsignedByte()
                records += BlockChangeRecord(horizontal shr 4, y, horizontal and 0xF, reader.readVarInt())
            }
            MultiBlockChange(chunkX, chunkZ, records)
        }

        ClientboundPlay.BLOCK_CHANGE -> BlockChange(reader.readPosition(), reader.readVarInt())
        ClientboundPlay.BLOCK_ACTION -> BlockAction(
            reader.readPosition(),
            reader.readUnsignedByte(),
            reader.readUnsignedByte(),
            reader.readVarInt()
        )

        ClientboundPlay.BLOCK_BREAK_ANIMATION -> BlockBreakAnimation(
            reader.readVarInt(),
            reader.readPosition(),
            reader.readByte().toInt()
        )

        ClientboundPlay.MAP_CHUNK_BULK -> readMapChunkBulk(reader)
        ClientboundPlay.EXPLOSION -> {
            val x = reader.readFloat()
            val y = reader.readFloat()
            val z = reader.readFloat()
            val radius = reader.readFloat()
            val count = reader.readInt()
            val blocks = ArrayList<MetaBlockPos>(count.coerceIn(0, 4096))
            repeat(count) {
                blocks += MetaBlockPos(
                    reader.readByte().toInt(),
                    reader.readByte().toInt(),
                    reader.readByte().toInt()
                )
            }
            Explosion(x, y, z, radius, blocks, reader.readFloat(), reader.readFloat(), reader.readFloat())
        }

        ClientboundPlay.EFFECT -> WorldEffect(
            reader.readInt(),
            reader.readPosition(),
            reader.readInt(),
            reader.readBoolean()
        )

        ClientboundPlay.SOUND_EFFECT -> SoundEffect(
            reader.readString(),
            reader.readInt(),
            reader.readInt(),
            reader.readInt(),
            reader.readFloat(),
            reader.readUnsignedByte()
        )

        ClientboundPlay.CHANGE_GAME_STATE -> ChangeGameState(reader.readUnsignedByte(), reader.readFloat())
        ClientboundPlay.SPAWN_GLOBAL_ENTITY -> SpawnGlobalEntity(
            reader.readVarInt(),
            reader.readByte().toInt(),
            reader.readInt(),
            reader.readInt(),
            reader.readInt()
        )

        ClientboundPlay.OPEN_WINDOW -> {
            val windowId = reader.readUnsignedByte()
            val type = reader.readString()
            val title = reader.readString()
            val slots = reader.readUnsignedByte()
            OpenWindow(windowId, type, title, slots, if (type == "EntityHorse") reader.readInt() else 0)
        }

        ClientboundPlay.CLOSE_WINDOW -> CloseWindow(reader.readUnsignedByte())
        ClientboundPlay.SET_SLOT -> SetSlot(
            reader.readByte().toInt(),
            reader.readShort().toInt(),
            SlotCodec.read(reader)
        )

        ClientboundPlay.WINDOW_ITEMS -> {
            val windowId = reader.readUnsignedByte()
            val count = reader.readShort().toInt()
            WindowItems(windowId, List(count) { SlotCodec.read(reader) })
        }

        ClientboundPlay.WINDOW_PROPERTY -> WindowProperty(
            reader.readUnsignedByte(),
            reader.readShort().toInt(),
            reader.readShort().toInt()
        )

        ClientboundPlay.CONFIRM_TRANSACTION -> ConfirmTransaction(
            reader.readByte().toInt(),
            reader.readShort().toInt(),
            reader.readBoolean()
        )

        ClientboundPlay.UPDATE_SIGN -> UpdateSign(reader.readPosition(), List(4) { reader.readString() })
        ClientboundPlay.MAP -> readMapData(reader)
        ClientboundPlay.UPDATE_BLOCK_ENTITY -> UpdateBlockEntity(
            reader.readPosition(),
            reader.readUnsignedByte(),
            reader.readNbt()
        )

        ClientboundPlay.OPEN_SIGN_EDITOR -> OpenSignEditor(reader.readPosition())
        ClientboundPlay.PLAYER_LIST_ITEM -> readPlayerListItem(reader)
        ClientboundPlay.PLAYER_ABILITIES -> PlayerAbilities(
            reader.readUnsignedByte(),
            reader.readFloat(),
            reader.readFloat()
        )

        ClientboundPlay.SCOREBOARD_OBJECTIVE -> {
            val name = reader.readString(16)
            val mode = reader.readByte().toInt()
            if (mode == ScoreboardObjective.CREATE || mode == ScoreboardObjective.UPDATE) {
                ScoreboardObjective(name, mode, reader.readString(32), reader.readString(16))
            } else {
                ScoreboardObjective(name, mode, null, null)
            }
        }

        ClientboundPlay.UPDATE_SCORE -> {
            val name = reader.readString(40)
            val action = reader.readByte().toInt()
            val objective = reader.readString(16)
            UpdateScore(name, action, objective, if (action != UpdateScore.REMOVE) reader.readVarInt() else 0)
        }

        ClientboundPlay.DISPLAY_SCOREBOARD -> DisplayScoreboard(reader.readByte().toInt(), reader.readString(16))
        ClientboundPlay.TEAMS -> readTeams(reader)
        ClientboundPlay.PLUGIN_MESSAGE -> PluginMessage(reader.readString(20), reader.readRemaining())
        ClientboundPlay.DISCONNECT -> Disconnect(reader.readString())
        ClientboundPlay.SERVER_DIFFICULTY -> ServerDifficulty(reader.readUnsignedByte())
        ClientboundPlay.COMBAT_EVENT -> when (val event = reader.readVarInt()) {
            CombatEvent.END_COMBAT -> CombatEvent(event, reader.readVarInt(), 0, reader.readInt(), null)
            CombatEvent.ENTITY_DEAD -> CombatEvent(event, 0, reader.readVarInt(), reader.readInt(), reader.readString())
            else -> CombatEvent(event, 0, 0, 0, null)
        }

        ClientboundPlay.CAMERA -> Camera(reader.readVarInt())
        ClientboundPlay.WORLD_BORDER -> readWorldBorder(reader)
        ClientboundPlay.TITLE -> when (val action = reader.readVarInt()) {
            Title.SET_TITLE, Title.SET_SUBTITLE -> Title(action, reader.readString(), 0, 0, 0)
            Title.SET_TIMES -> Title(action, null, reader.readInt(), reader.readInt(), reader.readInt())
            else -> Title(action, null, 0, 0, 0)
        }

        ClientboundPlay.SET_COMPRESSION -> SetCompression(reader.readVarInt())
        ClientboundPlay.PLAYER_LIST_HEADER_FOOTER -> PlayerListHeaderFooter(reader.readString(), reader.readString())
        ClientboundPlay.RESOURCE_PACK_SEND -> ResourcePackSend(reader.readString(), reader.readString(40))
        ClientboundPlay.UPDATE_ENTITY_NBT -> UpdateEntityNbt(reader.readVarInt(), reader.readNbt())
        else -> null
    }

    private fun readSpawnObject(reader: PacketReader): SpawnObject {
        val entityId = reader.readVarInt()
        val type = reader.readByte().toInt()
        val x = reader.readInt()
        val y = reader.readInt()
        val z = reader.readInt()
        val pitch = reader.readByte()
        val yaw = reader.readByte()
        val data = reader.readInt()
        if (data == 0) return SpawnObject(entityId, type, x, y, z, pitch, yaw, data, 0, 0, 0)
        return SpawnObject(
            entityId,
            type,
            x,
            y,
            z,
            pitch,
            yaw,
            data,
            reader.readShort().toInt(),
            reader.readShort().toInt(),
            reader.readShort().toInt()
        )
    }

    private fun readEntityProperties(reader: PacketReader): EntityProperties {
        val entityId = reader.readVarInt()
        val count = reader.readInt()
        val attributes = ArrayList<EntityAttribute>(count.coerceIn(0, 64))
        repeat(count) {
            val key = reader.readString(64)
            val value = reader.readDouble()
            val modifierCount = reader.readVarInt()
            val modifiers = ArrayList<AttributeModifier>(modifierCount.coerceIn(0, 64))
            repeat(modifierCount) {
                modifiers += AttributeModifier(
                    reader.readUuid(),
                    reader.readDouble(),
                    reader.readByte().toInt()
                )
            }
            attributes += EntityAttribute(key, value, modifiers)
        }
        return EntityProperties(entityId, attributes)
    }

    private fun readMapChunkBulk(reader: PacketReader): MapChunkBulk {
        val skyLight = reader.readBoolean()
        val count = reader.readVarInt()
        val xs = IntArray(count)
        val zs = IntArray(count)
        val masks = IntArray(count)
        for (index in 0 until count) {
            xs[index] = reader.readInt()
            zs[index] = reader.readInt()
            masks[index] = reader.readUnsignedShort()
        }
        val chunks = ArrayList<BulkChunk>(count)
        for (index in 0 until count) {
            val size = ChunkLayout.dataSize(masks[index], skyLight, groundUp = true)
            chunks += BulkChunk(xs[index], zs[index], masks[index], reader.readBytes(size))
        }
        return MapChunkBulk(skyLight, chunks)
    }

    private fun readMapData(reader: PacketReader): MapData {
        val mapId = reader.readVarInt()
        val scale = reader.readByte().toInt()
        val iconCount = reader.readVarInt()
        val icons = ArrayList<MapIcon>(iconCount.coerceIn(0, 256))
        repeat(iconCount) {
            icons += MapIcon(
                reader.readUnsignedByte(),
                reader.readByte().toInt(),
                reader.readByte().toInt()
            )
        }
        val columns = reader.readUnsignedByte()
        if (columns == 0) return MapData(mapId, scale, icons, 0, 0, 0, 0, ByteArray(0))
        val rows = reader.readUnsignedByte()
        val x = reader.readUnsignedByte()
        val z = reader.readUnsignedByte()
        val data = reader.readBytes(reader.readVarInt())
        return MapData(mapId, scale, icons, columns, rows, x, z, data)
    }

    private fun readPlayerListItem(reader: PacketReader): PlayerListItem {
        val action = reader.readVarInt()
        val count = reader.readVarInt()
        val entries = ArrayList<PlayerListEntry>(count.coerceIn(0, 1024))
        repeat(count) {
            val uuid = reader.readUuid()
            entries += when (action) {
                PlayerListItem.ADD_PLAYER -> {
                    val name = reader.readString(16)
                    val propertyCount = reader.readVarInt()
                    val properties = ArrayList<ProfileProperty>(propertyCount.coerceIn(0, 16))
                    repeat(propertyCount) {
                        val propertyName = reader.readString()
                        val value = reader.readString()
                        val signature = if (reader.readBoolean()) reader.readString() else null
                        properties += ProfileProperty(propertyName, value, signature)
                    }
                    val gameMode = reader.readVarInt()
                    val ping = reader.readVarInt()
                    val displayName = if (reader.readBoolean()) reader.readString() else null
                    PlayerListEntry(uuid, name, properties, gameMode, ping, displayName)
                }

                PlayerListItem.UPDATE_GAME_MODE -> PlayerListEntry(
                    uuid,
                    null,
                    emptyList(),
                    reader.readVarInt(),
                    0,
                    null
                )

                PlayerListItem.UPDATE_LATENCY -> PlayerListEntry(uuid, null, emptyList(), 0, reader.readVarInt(), null)
                PlayerListItem.UPDATE_DISPLAY_NAME -> PlayerListEntry(
                    uuid,
                    null,
                    emptyList(),
                    0,
                    0,
                    if (reader.readBoolean()) reader.readString() else null
                )

                else -> PlayerListEntry(uuid, null, emptyList(), 0, 0, null)
            }
        }
        return PlayerListItem(action, entries)
    }

    private fun readTeams(reader: PacketReader): Teams {
        val name = reader.readString(16)
        val mode = reader.readByte().toInt()
        var displayName: String? = null
        var prefix: String? = null
        var suffix: String? = null
        var friendlyFire = 0
        var visibility: String? = null
        var color = -1
        if (mode == Teams.CREATE || mode == Teams.UPDATE) {
            displayName = reader.readString(32)
            prefix = reader.readString(16)
            suffix = reader.readString(16)
            friendlyFire = reader.readByte().toInt()
            visibility = reader.readString(32)
            color = reader.readByte().toInt()
        }
        val players = if (mode == Teams.CREATE || mode == Teams.ADD_PLAYERS || mode == Teams.REMOVE_PLAYERS) {
            List(reader.readVarInt()) { reader.readString(40) }
        } else {
            emptyList()
        }
        return Teams(name, mode, displayName, prefix, suffix, friendlyFire, visibility, color, players)
    }

    private fun readWorldBorder(reader: PacketReader): WorldBorder = when (val action = reader.readVarInt()) {
        WorldBorder.SET_SIZE -> WorldBorder(action, reader.readDouble(), 0.0, 0.0, 0L, 0.0, 0.0, 0, 0, 0)
        WorldBorder.LERP_SIZE -> WorldBorder(
            action,
            0.0,
            reader.readDouble(),
            reader.readDouble(),
            reader.readVarLong(),
            0.0,
            0.0,
            0,
            0,
            0
        )

        WorldBorder.SET_CENTER -> WorldBorder(
            action,
            0.0,
            0.0,
            0.0,
            0L,
            reader.readDouble(),
            reader.readDouble(),
            0,
            0,
            0
        )

        WorldBorder.INITIALIZE -> {
            val centerX = reader.readDouble()
            val centerZ = reader.readDouble()
            val oldRadius = reader.readDouble()
            val newRadius = reader.readDouble()
            val speed = reader.readVarLong()
            WorldBorder(
                action,
                0.0,
                oldRadius,
                newRadius,
                speed,
                centerX,
                centerZ,
                reader.readVarInt(),
                reader.readVarInt(),
                reader.readVarInt()
            )
        }

        WorldBorder.SET_WARNING_TIME -> WorldBorder(action, 0.0, 0.0, 0.0, 0L, 0.0, 0.0, 0, reader.readVarInt(), 0)
        WorldBorder.SET_WARNING_BLOCKS -> WorldBorder(action, 0.0, 0.0, 0.0, 0L, 0.0, 0.0, 0, 0, reader.readVarInt())
        else -> WorldBorder(action, 0.0, 0.0, 0.0, 0L, 0.0, 0.0, 0, 0, 0)
    }

    fun encode(packet: ClientboundPacket, writer: PacketWriter) {
        when (packet) {
            is JoinGame -> writer.writeInt(packet.entityId).writeByte(packet.gameMode).writeByte(packet.dimension)
                .writeByte(packet.difficulty)
                .writeByte(packet.maxPlayers).writeString(packet.levelType).writeBoolean(packet.reducedDebugInfo)

            is ChatMessage -> writer.writeString(packet.json).writeByte(packet.position)
            is TimeUpdate -> writer.writeLong(packet.worldAge).writeLong(packet.timeOfDay)
            is EntityEquipment -> {
                writer.writeVarInt(packet.entityId).writeShort(packet.slot)
                SlotCodec.write(writer, packet.item)
            }

            is SpawnPosition -> writer.writePosition(packet.position)
            is UpdateHealth -> writer.writeFloat(packet.health).writeVarInt(packet.food).writeFloat(packet.saturation)
            is Respawn -> writer.writeInt(packet.dimension).writeByte(packet.difficulty).writeByte(packet.gameMode)
                .writeString(packet.levelType)

            is PlayerPositionAndLook -> writer.writeDouble(packet.x).writeDouble(packet.y).writeDouble(packet.z)
                .writeFloat(packet.yaw).writeFloat(packet.pitch).writeByte(packet.flags)

            is HeldItemChange -> writer.writeByte(packet.slot)
            is UseBed -> writer.writeVarInt(packet.entityId).writePosition(packet.position)
            is Animation -> writer.writeVarInt(packet.entityId).writeByte(packet.animation)
            is SpawnPlayer -> {
                writer.writeVarInt(packet.entityId).writeUuid(packet.uuid).writeInt(packet.x).writeInt(packet.y)
                    .writeInt(packet.z)
                    .writeByte(packet.yaw.toInt()).writeByte(packet.pitch.toInt()).writeShort(packet.currentItem)
                MetadataCodec.write(writer, packet.metadata)
            }

            is CollectItem -> writer.writeVarInt(packet.collectedId).writeVarInt(packet.collectorId)
            is SpawnObject -> {
                writer.writeVarInt(packet.entityId).writeByte(packet.type).writeInt(packet.x).writeInt(packet.y)
                    .writeInt(packet.z)
                    .writeByte(packet.pitch.toInt()).writeByte(packet.yaw.toInt()).writeInt(packet.data)
                if (packet.data != 0) writer.writeShort(packet.velocityX).writeShort(packet.velocityY)
                    .writeShort(packet.velocityZ)
            }

            is SpawnMob -> {
                writer.writeVarInt(packet.entityId).writeByte(packet.type).writeInt(packet.x).writeInt(packet.y)
                    .writeInt(packet.z)
                    .writeByte(packet.yaw.toInt()).writeByte(packet.pitch.toInt()).writeByte(packet.headPitch.toInt())
                    .writeShort(packet.velocityX).writeShort(packet.velocityY).writeShort(packet.velocityZ)
                MetadataCodec.write(writer, packet.metadata)
            }

            is SpawnPainting -> writer.writeVarInt(packet.entityId).writeString(packet.title)
                .writePosition(packet.position).writeByte(packet.facing)

            is SpawnExperienceOrb -> writer.writeVarInt(packet.entityId).writeInt(packet.x).writeInt(packet.y)
                .writeInt(packet.z).writeShort(packet.count)

            is EntityVelocity -> writer.writeVarInt(packet.entityId).writeShort(packet.velocityX)
                .writeShort(packet.velocityY).writeShort(packet.velocityZ)

            is DestroyEntities -> {
                writer.writeVarInt(packet.entityIds.size)
                for (id in packet.entityIds) writer.writeVarInt(id)
            }

            is EntityIdle -> writer.writeVarInt(packet.entityId)
            is EntityRelativeMove -> writer.writeVarInt(packet.entityId).writeByte(packet.deltaX.toInt())
                .writeByte(packet.deltaY.toInt()).writeByte(packet.deltaZ.toInt()).writeBoolean(packet.onGround)

            is EntityLook -> writer.writeVarInt(packet.entityId).writeByte(packet.yaw.toInt())
                .writeByte(packet.pitch.toInt()).writeBoolean(packet.onGround)

            is EntityLookAndRelativeMove -> writer.writeVarInt(packet.entityId).writeByte(packet.deltaX.toInt())
                .writeByte(packet.deltaY.toInt()).writeByte(packet.deltaZ.toInt())
                .writeByte(packet.yaw.toInt()).writeByte(packet.pitch.toInt()).writeBoolean(packet.onGround)

            is EntityTeleport -> writer.writeVarInt(packet.entityId).writeInt(packet.x).writeInt(packet.y)
                .writeInt(packet.z).writeByte(packet.yaw.toInt()).writeByte(packet.pitch.toInt())
                .writeBoolean(packet.onGround)

            is EntityHeadLook -> writer.writeVarInt(packet.entityId).writeByte(packet.headYaw.toInt())
            is EntityStatus -> writer.writeInt(packet.entityId).writeByte(packet.status)
            is AttachEntity -> writer.writeInt(packet.entityId).writeInt(packet.vehicleId).writeBoolean(packet.leash)
            is EntityMetadata -> {
                writer.writeVarInt(packet.entityId)
                MetadataCodec.write(writer, packet.metadata)
            }

            is EntityEffect -> writer.writeVarInt(packet.entityId).writeByte(packet.effectId)
                .writeByte(packet.amplifier).writeVarInt(packet.duration).writeBoolean(packet.hideParticles)

            is RemoveEntityEffect -> writer.writeVarInt(packet.entityId).writeByte(packet.effectId)
            is SetExperience -> writer.writeFloat(packet.bar).writeVarInt(packet.level).writeVarInt(packet.total)
            is EntityProperties -> {
                writer.writeVarInt(packet.entityId).writeInt(packet.attributes.size)
                for (attribute in packet.attributes) {
                    writer.writeString(attribute.key).writeDouble(attribute.value).writeVarInt(attribute.modifiers.size)
                    for (modifier in attribute.modifiers) writer.writeUuid(modifier.uuid).writeDouble(modifier.amount)
                        .writeByte(modifier.operation)
                }
            }

            is ChunkData -> writer.writeInt(packet.chunkX).writeInt(packet.chunkZ).writeBoolean(packet.groundUp)
                .writeShort(packet.mask).writeVarInt(packet.data.size).writeBytes(packet.data)

            is MultiBlockChange -> {
                writer.writeInt(packet.chunkX).writeInt(packet.chunkZ).writeVarInt(packet.records.size)
                for (record in packet.records) {
                    writer.writeByte(((record.x and 0xF) shl 4) or (record.z and 0xF)).writeByte(record.y)
                        .writeVarInt(record.blockState)
                }
            }

            is BlockChange -> writer.writePosition(packet.position).writeVarInt(packet.blockState)
            is BlockAction -> writer.writePosition(packet.position).writeByte(packet.byte1).writeByte(packet.byte2)
                .writeVarInt(packet.blockType)

            is BlockBreakAnimation -> writer.writeVarInt(packet.entityId).writePosition(packet.position)
                .writeByte(packet.stage)

            is MapChunkBulk -> {
                writer.writeBoolean(packet.skyLight).writeVarInt(packet.chunks.size)
                for (chunk in packet.chunks) writer.writeInt(chunk.chunkX).writeInt(chunk.chunkZ).writeShort(chunk.mask)
                for (chunk in packet.chunks) writer.writeBytes(chunk.data)
            }

            is Explosion -> {
                writer.writeFloat(packet.x).writeFloat(packet.y).writeFloat(packet.z).writeFloat(packet.radius)
                    .writeInt(packet.affectedBlocks.size)
                for (block in packet.affectedBlocks) writer.writeByte(block.x).writeByte(block.y).writeByte(block.z)
                writer.writeFloat(packet.motionX).writeFloat(packet.motionY).writeFloat(packet.motionZ)
            }

            is WorldEffect -> writer.writeInt(packet.effectId).writePosition(packet.position).writeInt(packet.data)
                .writeBoolean(packet.disableRelativeVolume)

            is SoundEffect -> writer.writeString(packet.name).writeInt(packet.x).writeInt(packet.y).writeInt(packet.z)
                .writeFloat(packet.volume).writeByte(packet.pitch)

            is ChangeGameState -> writer.writeByte(packet.reason).writeFloat(packet.value)
            is SpawnGlobalEntity -> writer.writeVarInt(packet.entityId).writeByte(packet.type).writeInt(packet.x)
                .writeInt(packet.y).writeInt(packet.z)

            is OpenWindow -> {
                writer.writeByte(packet.windowId).writeString(packet.type).writeString(packet.titleJson)
                    .writeByte(packet.slotCount)
                if (packet.type == "EntityHorse") writer.writeInt(packet.horseEntityId)
            }

            is CloseWindow -> writer.writeByte(packet.windowId)
            is SetSlot -> {
                writer.writeByte(packet.windowId).writeShort(packet.slot)
                SlotCodec.write(writer, packet.item)
            }

            is WindowItems -> {
                writer.writeByte(packet.windowId).writeShort(packet.items.size)
                for (item in packet.items) SlotCodec.write(writer, item)
            }

            is WindowProperty -> writer.writeByte(packet.windowId).writeShort(packet.property).writeShort(packet.value)
            is ConfirmTransaction -> writer.writeByte(packet.windowId).writeShort(packet.action)
                .writeBoolean(packet.accepted)

            is UpdateSign -> {
                writer.writePosition(packet.position)
                for (index in 0 until 4) writer.writeString(packet.lines.getOrElse(index) { "" })
            }

            is MapData -> {
                writer.writeVarInt(packet.mapId).writeByte(packet.scale).writeVarInt(packet.icons.size)
                for (icon in packet.icons) writer.writeByte(icon.directionAndType).writeByte(icon.x).writeByte(icon.z)
                writer.writeByte(packet.columns)
                if (packet.columns > 0) writer.writeByte(packet.rows).writeByte(packet.x).writeByte(packet.z)
                    .writeVarInt(packet.data.size).writeBytes(packet.data)
            }

            is UpdateBlockEntity -> writer.writePosition(packet.position).writeByte(packet.action).writeNbt(packet.nbt)
            is OpenSignEditor -> writer.writePosition(packet.position)
            is PlayerListItem -> encodePlayerListItem(packet, writer)
            is PlayerAbilities -> writer.writeByte(packet.flags).writeFloat(packet.flyingSpeed)
                .writeFloat(packet.walkingSpeed)

            is ScoreboardObjective -> {
                writer.writeString(packet.name).writeByte(packet.mode)
                if (packet.mode == ScoreboardObjective.CREATE || packet.mode == ScoreboardObjective.UPDATE) {
                    writer.writeString(packet.displayName ?: packet.name).writeString(packet.type ?: "integer")
                }
            }

            is UpdateScore -> {
                writer.writeString(packet.name).writeByte(packet.action).writeString(packet.objective)
                if (packet.action != UpdateScore.REMOVE) writer.writeVarInt(packet.value)
            }

            is DisplayScoreboard -> writer.writeByte(packet.position).writeString(packet.name)
            is Teams -> {
                writer.writeString(packet.name).writeByte(packet.mode)
                if (packet.mode == Teams.CREATE || packet.mode == Teams.UPDATE) {
                    writer.writeString(packet.displayName ?: packet.name).writeString(packet.prefix ?: "")
                        .writeString(packet.suffix ?: "")
                        .writeByte(packet.friendlyFire).writeString(packet.nameTagVisibility ?: "always")
                        .writeByte(packet.color)
                }
                if (packet.mode == Teams.CREATE || packet.mode == Teams.ADD_PLAYERS || packet.mode == Teams.REMOVE_PLAYERS) {
                    writer.writeVarInt(packet.players.size)
                    for (player in packet.players) writer.writeString(player)
                }
            }

            is PluginMessage -> writer.writeString(packet.channel).writeBytes(packet.data)
            is Disconnect -> writer.writeString(packet.reasonJson)
            is ServerDifficulty -> writer.writeByte(packet.difficulty)
            is CombatEvent -> {
                writer.writeVarInt(packet.event)
                when (packet.event) {
                    CombatEvent.END_COMBAT -> writer.writeVarInt(packet.duration).writeInt(packet.entityId)
                    CombatEvent.ENTITY_DEAD -> writer.writeVarInt(packet.playerId).writeInt(packet.entityId)
                        .writeString(packet.messageJson ?: "")
                }
            }

            is Camera -> writer.writeVarInt(packet.cameraEntityId)
            is WorldBorder -> {
                writer.writeVarInt(packet.action)
                when (packet.action) {
                    WorldBorder.SET_SIZE -> writer.writeDouble(packet.radius)
                    WorldBorder.LERP_SIZE -> writer.writeDouble(packet.oldRadius).writeDouble(packet.newRadius)
                        .writeVarLong(packet.speed)

                    WorldBorder.SET_CENTER -> writer.writeDouble(packet.centerX).writeDouble(packet.centerZ)
                    WorldBorder.INITIALIZE -> writer.writeDouble(packet.centerX).writeDouble(packet.centerZ)
                        .writeDouble(packet.oldRadius).writeDouble(packet.newRadius)
                        .writeVarLong(packet.speed).writeVarInt(packet.portalTeleportBoundary)
                        .writeVarInt(packet.warningTime).writeVarInt(packet.warningBlocks)

                    WorldBorder.SET_WARNING_TIME -> writer.writeVarInt(packet.warningTime)
                    WorldBorder.SET_WARNING_BLOCKS -> writer.writeVarInt(packet.warningBlocks)
                }
            }

            is Title -> {
                writer.writeVarInt(packet.action)
                when (packet.action) {
                    Title.SET_TITLE, Title.SET_SUBTITLE -> writer.writeString(packet.textJson ?: "")
                    Title.SET_TIMES -> writer.writeInt(packet.fadeIn).writeInt(packet.stay).writeInt(packet.fadeOut)
                }
            }

            is SetCompression -> writer.writeVarInt(packet.threshold)
            is PlayerListHeaderFooter -> writer.writeString(packet.headerJson).writeString(packet.footerJson)
            is ResourcePackSend -> writer.writeString(packet.url).writeString(packet.hash)
            is UpdateEntityNbt -> writer.writeVarInt(packet.entityId).writeNbt(packet.nbt)
        }
    }

    private fun encodePlayerListItem(packet: PlayerListItem, writer: PacketWriter) {
        writer.writeVarInt(packet.action).writeVarInt(packet.entries.size)
        for (entry in packet.entries) {
            writer.writeUuid(entry.uuid)
            when (packet.action) {
                PlayerListItem.ADD_PLAYER -> {
                    writer.writeString(entry.name ?: "").writeVarInt(entry.properties.size)
                    for (property in entry.properties) {
                        writer.writeString(property.name).writeString(property.value)
                            .writeBoolean(property.signature != null)
                        if (property.signature != null) writer.writeString(property.signature)
                    }
                    writer.writeVarInt(entry.gameMode).writeVarInt(entry.ping)
                        .writeBoolean(entry.displayNameJson != null)
                    if (entry.displayNameJson != null) writer.writeString(entry.displayNameJson)
                }

                PlayerListItem.UPDATE_GAME_MODE -> writer.writeVarInt(entry.gameMode)
                PlayerListItem.UPDATE_LATENCY -> writer.writeVarInt(entry.ping)
                PlayerListItem.UPDATE_DISPLAY_NAME -> {
                    writer.writeBoolean(entry.displayNameJson != null)
                    if (entry.displayNameJson != null) writer.writeString(entry.displayNameJson)
                }
            }
        }
    }
}
