package gg.sona.recast.protocol

import gg.sona.recast.net.PacketReader
import gg.sona.recast.net.PacketWriter

object RecastChannel {

    const val NAME = "recast:v1"
    const val REGISTER_CHANNEL = "REGISTER"
    const val LOOK = 1
    const val HEALTH = 2
    const val EQUIPMENT = 3
    const val POSITION = 4

    fun registrationPayload(): ByteArray = NAME.toByteArray(Charsets.UTF_8)

    fun translate(data: ByteArray): List<ClientboundPacket> {
        val reader = PacketReader(data, 0, data.size)
        val result = ArrayList<ClientboundPacket>()
        while (reader.hasRemaining()) {
            when (reader.readUnsignedByte()) {
                LOOK -> {
                    val entityId = reader.readVarInt()
                    val yaw = reader.readFloat()
                    val pitch = reader.readFloat()
                    val headYaw = reader.readFloat()
                    result += EntityLook(entityId, angle(yaw), angle(pitch), true)
                    result += EntityHeadLook(entityId, angle(headYaw))
                }

                HEALTH -> {
                    val entityId = reader.readVarInt()
                    val health = reader.readFloat()
                    result += EntityMetadata(entityId, listOf(MetadataEntry(6, MetadataEntry.FLOAT, health)))
                }

                EQUIPMENT -> {
                    val entityId = reader.readVarInt()
                    val slot = reader.readUnsignedByte()
                    result += EntityEquipment(entityId, slot, SlotCodec.read(reader))
                }

                POSITION -> {
                    val entityId = reader.readVarInt()
                    val x = reader.readDouble()
                    val y = reader.readDouble()
                    val z = reader.readDouble()
                    val yaw = reader.readFloat()
                    val pitch = reader.readFloat()
                    result += EntityTeleport(
                        entityId,
                        Protocol.toFixed(x),
                        Protocol.toFixed(y),
                        Protocol.toFixed(z),
                        angle(yaw),
                        angle(pitch),
                        true
                    )
                }

                else -> return result
            }
        }
        return result
    }

    fun encodeLook(entityId: Int, yaw: Float, pitch: Float, headYaw: Float): ByteArray {
        val writer = PacketWriter(32)
        writer.writeByte(LOOK).writeVarInt(entityId).writeFloat(yaw).writeFloat(pitch).writeFloat(headYaw)
        return writer.toByteArray()
    }

    fun encodeHealth(entityId: Int, health: Float): ByteArray {
        val writer = PacketWriter(16)
        writer.writeByte(HEALTH).writeVarInt(entityId).writeFloat(health)
        return writer.toByteArray()
    }

    fun encodePosition(entityId: Int, x: Double, y: Double, z: Double, yaw: Float, pitch: Float): ByteArray {
        val writer = PacketWriter(48)
        writer.writeByte(POSITION).writeVarInt(entityId).writeDouble(x).writeDouble(y).writeDouble(z).writeFloat(yaw)
            .writeFloat(pitch)
        return writer.toByteArray()
    }

    private fun angle(degrees: Float): Byte = Math.floor(degrees * 256.0 / 360.0).toInt().toByte()
}
