package gg.sona.recast.protocol

import gg.sona.recast.net.*

object EntityPacketIds {

    private val varIntEntityIds = BooleanArray(ClientboundPlay.COUNT).also { table ->
        for (id in intArrayOf(
            ClientboundPlay.ENTITY_EQUIPMENT,
            ClientboundPlay.USE_BED,
            ClientboundPlay.ANIMATION,
            ClientboundPlay.SPAWN_PLAYER,
            ClientboundPlay.SPAWN_OBJECT,
            ClientboundPlay.SPAWN_MOB,
            ClientboundPlay.SPAWN_PAINTING,
            ClientboundPlay.SPAWN_EXPERIENCE_ORB,
            ClientboundPlay.ENTITY_VELOCITY,
            ClientboundPlay.ENTITY,
            ClientboundPlay.ENTITY_RELATIVE_MOVE,
            ClientboundPlay.ENTITY_LOOK,
            ClientboundPlay.ENTITY_LOOK_AND_RELATIVE_MOVE,
            ClientboundPlay.ENTITY_TELEPORT,
            ClientboundPlay.ENTITY_HEAD_LOOK,
            ClientboundPlay.ENTITY_METADATA,
            ClientboundPlay.ENTITY_EFFECT,
            ClientboundPlay.REMOVE_ENTITY_EFFECT,
            ClientboundPlay.ENTITY_PROPERTIES,
            ClientboundPlay.BLOCK_BREAK_ANIMATION,
            ClientboundPlay.SPAWN_GLOBAL_ENTITY,
            ClientboundPlay.UPDATE_ENTITY_NBT,
        )) {
            table[id] = true
        }
    }

    private val intEntityIds = BooleanArray(ClientboundPlay.COUNT).also { table ->
        table[ClientboundPlay.ENTITY_STATUS] = true
        table[ClientboundPlay.ATTACH_ENTITY] = true
    }

    const val NONE = Int.MIN_VALUE

    fun targetOf(packet: CapturedPacket): Int {
        if (packet.direction != PacketDirection.CLIENTBOUND) return NONE
        val id = packet.packetId
        if (id < 0 || id >= ClientboundPlay.COUNT) return NONE
        val payload = packet.payload
        return when {
            varIntEntityIds[id] -> if (payload.isEmpty()) NONE else VarInts.value(
                VarInts.read(
                    payload,
                    0,
                    payload.size
                )
            )

            intEntityIds[id] -> if (payload.size < 4) NONE else PacketReader(payload, 0, payload.size).readInt()
            else -> NONE
        }
    }

    fun rewriteTarget(packet: CapturedPacket, newEntityId: Int): CapturedPacket {
        val id = packet.packetId
        val payload = packet.payload
        return when {
            varIntEntityIds[id] -> {
                val oldLength = VarInts.length(VarInts.read(payload, 0, payload.size))
                val writer = PacketWriter(payload.size + 5)
                writer.writeVarInt(newEntityId)
                writer.writeBytes(payload, oldLength, payload.size - oldLength)
                packet.withPayload(writer.toByteArray())
            }

            intEntityIds[id] -> {
                val writer = PacketWriter(payload.size)
                writer.writeInt(newEntityId)
                writer.writeBytes(payload, 4, payload.size - 4)
                packet.withPayload(writer.toByteArray())
            }

            else -> packet
        }
    }
}
