package gg.sona.afterimage.protocol

import gg.sona.afterimage.net.PacketReader
import gg.sona.afterimage.net.PacketWriter

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
        else -> if (AfterimageInternal.isInternal(packetId)) InternalCodec.decode(packetId, reader) else null
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
            else -> require(InternalCodec.encodeInto(packet, writer)) { "unknown serverbound packet $packet" }
        }
    }
}
