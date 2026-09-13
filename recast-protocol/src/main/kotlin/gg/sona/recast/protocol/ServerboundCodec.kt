package gg.sona.recast.protocol

import gg.sona.recast.net.PacketReader
import gg.sona.recast.net.PacketWriter

object ServerboundCodec {

    fun decode(packetId: Int, reader: PacketReader): ServerboundPacket? = when (packetId) {
        ServerboundPlay.CHAT_MESSAGE -> ClientChatMessage(reader.readString(100))
        ServerboundPlay.USE_ENTITY -> {
            val target = reader.readVarInt()
            val type = reader.readVarInt()
            if (type == UseEntity.INTERACT_AT) UseEntity(
                target,
                type,
                reader.readFloat(),
                reader.readFloat(),
                reader.readFloat()
            )
            else UseEntity(target, type, 0f, 0f, 0f)
        }

        ServerboundPlay.PLAYER -> ClientPlayerGround(reader.readBoolean())
        ServerboundPlay.PLAYER_POSITION -> ClientPlayerPosition(
            reader.readDouble(),
            reader.readDouble(),
            reader.readDouble(),
            reader.readBoolean()
        )

        ServerboundPlay.PLAYER_LOOK -> ClientPlayerLook(reader.readFloat(), reader.readFloat(), reader.readBoolean())
        ServerboundPlay.PLAYER_POSITION_AND_LOOK -> ClientPlayerPositionAndLook(
            reader.readDouble(),
            reader.readDouble(),
            reader.readDouble(),
            reader.readFloat(),
            reader.readFloat(),
            reader.readBoolean(),
        )

        ServerboundPlay.PLAYER_DIGGING -> PlayerDigging(
            reader.readUnsignedByte(),
            reader.readPosition(),
            reader.readUnsignedByte()
        )

        ServerboundPlay.PLAYER_BLOCK_PLACEMENT -> PlayerBlockPlacement(
            reader.readPosition(),
            reader.readUnsignedByte(),
            SlotCodec.read(reader),
            reader.readUnsignedByte(),
            reader.readUnsignedByte(),
            reader.readUnsignedByte(),
        )

        ServerboundPlay.HELD_ITEM_CHANGE -> ClientHeldItemChange(reader.readShort().toInt())
        ServerboundPlay.ANIMATION -> ClientArmSwing
        ServerboundPlay.ENTITY_ACTION -> EntityAction(reader.readVarInt(), reader.readVarInt(), reader.readVarInt())
        ServerboundPlay.CLOSE_WINDOW -> ClientCloseWindow(reader.readUnsignedByte())
        ServerboundPlay.PLAYER_ABILITIES -> ClientPlayerAbilities(
            reader.readUnsignedByte(),
            reader.readFloat(),
            reader.readFloat()
        )

        ServerboundPlay.CLIENT_STATUS -> ClientStatus(reader.readVarInt())
        ServerboundPlay.PLUGIN_MESSAGE -> ClientPluginMessage(reader.readString(20), reader.readRemaining())
        ServerboundPlay.SPECTATE -> Spectate(reader.readUuid())
        RecastInternal.LOCAL_POSE -> LocalPose(
            reader.readDouble(),
            reader.readDouble(),
            reader.readDouble(),
            reader.readFloat(),
            reader.readFloat()
        )

        RecastInternal.SESSION_MARK -> SessionMark(reader.readVarInt(), reader.readString())
        RecastInternal.LOCAL_BLOCK_BREAK -> LocalBlockBreak(reader.readPosition(), reader.readByte().toInt())
        RecastInternal.LOCAL_TICK -> LocalTick
        RecastInternal.LOCAL_BLOCK_CHANGE -> LocalBlockChange(reader.readPosition(), reader.readVarInt())
        RecastInternal.LOCAL_TARGET -> LocalTarget(reader.readPosition(), reader.readByte().toInt())
        RecastInternal.LOCAL_SCREEN -> LocalScreen(
            reader.readUnsignedByte(),
            reader.readUnsignedByte(),
            reader.readShort().toInt(),
            reader.readShort().toInt(),
            reader.readShort().toInt(),
            reader.readShort().toInt(),
            reader.readString(LocalScreen.MAX_TEXT),
            reader.readVarInt(),
            reader.readVarInt(),
            reader.readLong(),
            reader.readVarInt(),
        )

        RecastInternal.CAMERA_FRAME -> readCameraFrame(reader)
        RecastInternal.CAMERA_FRAME_COMPACT -> readCompactCameraFrame(reader)
        RecastInternal.OVERLAY_RESET -> OverlayReset(
            reader.readVarInt(),
            List(reader.readVarInt()) { OverlayChatLine(reader.readVarLong(), reader.readString()) })

        else -> null
    }

    fun encode(packet: ServerboundPacket, writer: PacketWriter) {
        when (packet) {
            is ClientChatMessage -> writer.writeString(packet.message)
            is UseEntity -> {
                writer.writeVarInt(packet.targetId).writeVarInt(packet.type)
                if (packet.type == UseEntity.INTERACT_AT) writer.writeFloat(packet.hitX).writeFloat(packet.hitY)
                    .writeFloat(packet.hitZ)
            }

            is ClientPlayerGround -> writer.writeBoolean(packet.onGround)
            is ClientPlayerPosition -> writer.writeDouble(packet.x).writeDouble(packet.y).writeDouble(packet.z)
                .writeBoolean(packet.onGround)

            is ClientPlayerLook -> writer.writeFloat(packet.yaw).writeFloat(packet.pitch).writeBoolean(packet.onGround)
            is ClientPlayerPositionAndLook -> writer.writeDouble(packet.x).writeDouble(packet.y).writeDouble(packet.z)
                .writeFloat(packet.yaw).writeFloat(packet.pitch).writeBoolean(packet.onGround)

            is PlayerDigging -> writer.writeByte(packet.status).writePosition(packet.position).writeByte(packet.face)
            is PlayerBlockPlacement -> {
                writer.writePosition(packet.position).writeByte(packet.face)
                SlotCodec.write(writer, packet.heldItem)
                writer.writeByte(packet.cursorX).writeByte(packet.cursorY).writeByte(packet.cursorZ)
            }

            is ClientHeldItemChange -> writer.writeShort(packet.slot)
            ClientArmSwing -> Unit
            is EntityAction -> writer.writeVarInt(packet.entityId).writeVarInt(packet.action)
                .writeVarInt(packet.jumpBoost)

            is ClientCloseWindow -> writer.writeByte(packet.windowId)
            is ClientPlayerAbilities -> writer.writeByte(packet.flags).writeFloat(packet.flyingSpeed)
                .writeFloat(packet.walkingSpeed)

            is ClientStatus -> writer.writeVarInt(packet.action)
            is ClientPluginMessage -> writer.writeString(packet.channel).writeBytes(packet.data)
            is Spectate -> writer.writeUuid(packet.target)
            is LocalPose -> writer.writeDouble(packet.x).writeDouble(packet.y).writeDouble(packet.z)
                .writeFloat(packet.yaw).writeFloat(packet.pitch)

            is SessionMark -> writer.writeVarInt(packet.kind).writeString(packet.label)
            is LocalBlockBreak -> writer.writePosition(packet.position).writeByte(packet.stage)
            LocalTick -> Unit
            is LocalBlockChange -> writer.writePosition(packet.position).writeVarInt(packet.state)
            is LocalTarget -> writer.writePosition(packet.position).writeByte(packet.face)
            is LocalScreen -> writer.writeByte(packet.kind).writeByte(packet.windowId).writeShort(packet.mouseX)
                .writeShort(packet.mouseY)
                .writeShort(packet.guiWidth).writeShort(packet.guiHeight).writeString(packet.text)
                .writeVarInt(packet.cursor).writeVarInt(packet.detail)
                .writeLong(packet.position).writeVarInt(packet.flags)

            is OverlayReset -> {
                writer.writeVarInt(packet.flags).writeVarInt(packet.chat.size)
                for (line in packet.chat) writer.writeVarLong(line.ageNanos).writeString(line.json)
            }

            is CameraFrame -> {
                var flags = 0
                val viewRigid = RigidTransform.isRigid(packet.modelView)
                val handRigid = packet.hand != null && RigidTransform.isRigid(packet.hand)
                if (packet.position != null) flags = flags or CameraFrame.FLAG_POSITION
                if (packet.hand != null) flags = flags or CameraFrame.FLAG_HAND
                if (viewRigid) flags = flags or CameraFrame.FLAG_VIEW_COMPACT
                if (handRigid) flags = flags or CameraFrame.FLAG_HAND_COMPACT
                writer.writeByte(flags)
                writer.writeFloat(packet.fov)
                if (viewRigid) RigidTransform.write(
                    writer,
                    packet.modelView
                ) else for (value in packet.modelView) writer.writeFloat(value)
                packet.position?.forEach { writer.writeDouble(it) }
                if (packet.hand != null) {
                    if (handRigid) RigidTransform.write(
                        writer,
                        packet.hand
                    ) else for (value in packet.hand) writer.writeFloat(value)
                }
            }
        }
    }
}

private fun readCameraFrame(reader: PacketReader): CameraFrame {
    val modelView = FloatArray(16) { reader.readFloat() }
    val fov = reader.readFloat()
    if (reader.remaining < 1) return CameraFrame(modelView, fov)
    val flags = reader.readUnsignedByte()
    val position = if (flags and CameraFrame.FLAG_POSITION != 0) DoubleArray(3) { reader.readDouble() } else null
    val hand = if (flags and CameraFrame.FLAG_HAND != 0) FloatArray(16) { reader.readFloat() } else null
    return CameraFrame(modelView, fov, position, hand)
}

private fun readCompactCameraFrame(reader: PacketReader): CameraFrame {
    val flags = reader.readUnsignedByte()
    val fov = reader.readFloat()
    val modelView =
        if (flags and CameraFrame.FLAG_VIEW_COMPACT != 0) RigidTransform.read(reader) else FloatArray(16) { reader.readFloat() }
    val position = if (flags and CameraFrame.FLAG_POSITION != 0) DoubleArray(3) { reader.readDouble() } else null
    val hand = when {
        flags and CameraFrame.FLAG_HAND == 0 -> null
        flags and CameraFrame.FLAG_HAND_COMPACT != 0 -> RigidTransform.read(reader)
        else -> FloatArray(16) { reader.readFloat() }
    }
    return CameraFrame(modelView, fov, position, hand)
}
