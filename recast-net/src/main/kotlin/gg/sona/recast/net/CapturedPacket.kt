package gg.sona.recast.net

class CapturedPacket(
    val direction: PacketDirection,
    val timestampNanos: Long,
    val packetId: Int,
    val payload: ByteArray,
) {
    val payloadLength: Int get() = payload.size

    fun reader(): PacketReader = PacketReader(payload, 0, payload.size)

    fun withTimestamp(nanos: Long): CapturedPacket =
        if (nanos == timestampNanos) this else CapturedPacket(direction, nanos, packetId, payload)

    fun withPayload(newPayload: ByteArray): CapturedPacket =
        CapturedPacket(direction, timestampNanos, packetId, newPayload)

    fun isClientbound(): Boolean = direction == PacketDirection.CLIENTBOUND

    fun isServerbound(): Boolean = direction == PacketDirection.SERVERBOUND

    override fun toString(): String =
        "CapturedPacket($direction 0x${Integer.toHexString(packetId)} @${timestampNanos}ns ${payload.size}b)"
}
