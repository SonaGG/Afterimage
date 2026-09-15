package gg.sona.afterimage.mc26.protocol

import gg.sona.afterimage.mc26.net.PacketIds26
import gg.sona.afterimage.net.CapturedPacket
import gg.sona.afterimage.net.PacketDirection
import gg.sona.afterimage.net.PacketReader
import gg.sona.afterimage.protocol.AfterimageInternal
import gg.sona.afterimage.protocol.CameraFrame
import gg.sona.afterimage.protocol.InternalCodec
import gg.sona.afterimage.protocol.SessionMark
import gg.sona.afterimage.replay.protocol.ReplayProtocol
import gg.sona.afterimage.mc26.state.ShadowClient26
import gg.sona.afterimage.replay.protocol.ReplayState
import gg.sona.afterimage.world.RecorderIdentity
import gg.sona.afterimage.world.camera.CameraSample

class Protocol26 : ReplayProtocol {
    override val version: Int get() = PacketIds26.protocolVersion

    override val minecraftVersion: String get() = MINECRAFT_VERSION

    override fun createState(identity: RecorderIdentity): ReplayState = ShadowClient26(identity)

    override fun isJoin(packet: CapturedPacket): Boolean =
        packet.direction == PacketDirection.CLIENTBOUND && packet.packetId == PacketIds26.LOGIN

    override fun isTickMarker(packet: CapturedPacket): Boolean =
        packet.direction == PacketDirection.SERVERBOUND && packet.packetId == AfterimageInternal.LOCAL_TICK

    override fun respawnDimension(packet: CapturedPacket): Int? {
        if (packet.direction != PacketDirection.CLIENTBOUND || packet.packetId != PacketIds26.RESPAWN) return null
        return runCatching { dimensionId(readSpawnDimension(packet.reader())) }.getOrNull()
    }

    override fun cameraSample(packet: CapturedPacket): CameraSample? {
        if (packet.direction != PacketDirection.SERVERBOUND) return null
        if (packet.packetId != AfterimageInternal.CAMERA_FRAME && packet.packetId != AfterimageInternal.CAMERA_FRAME_COMPACT) return null
        val frame = InternalCodec.decode(packet) as? CameraFrame ?: return null
        return CameraSample(packet.timestampNanos, frame.modelView, frame.fov, frame.position, frame.hand)
    }

    override fun isTransient(packet: CapturedPacket): Boolean {
        if (packet.direction != PacketDirection.CLIENTBOUND) return packet.packetId == AfterimageInternal.LOCAL_BLOCK_BREAK
        return packet.packetId in PacketIds26.TRANSIENT
    }

    override fun isVisualEvent(packet: CapturedPacket): Boolean {
        if (packet.direction != PacketDirection.CLIENTBOUND) return packet.packetId == AfterimageInternal.LOCAL_BLOCK_BREAK
        return packet.packetId in PacketIds26.VISUAL_EVENTS
    }

    override fun marker(kind: Int, label: String, nanos: Long): CapturedPacket = InternalCodec.encode(SessionMark(kind, label), nanos)

    companion object {
        const val MINECRAFT_VERSION = "26.3"
        val INSTANCE = Protocol26()

        fun readSpawnDimension(reader: PacketReader): String {
            reader.readVarInt()
            return reader.readString(256)
        }

        fun readLoginDimension(reader: PacketReader): String {
            reader.readInt()
            reader.readBoolean()
            repeat(reader.readVarInt()) { reader.readString(256) }
            reader.readVarInt()
            reader.readVarInt()
            reader.readVarInt()
            reader.readBoolean()
            reader.readBoolean()
            reader.readBoolean()
            return readSpawnDimension(reader)
        }

        fun dimensionId(key: String): Int = when (key.removePrefix("minecraft:")) {
            "overworld" -> 0
            "the_nether" -> -1
            "the_end" -> 1
            else -> 2 + (key.hashCode() and 0x3FFFFFFF)
        }

        fun dimensionName(dimension: Int): String = when (dimension) {
            -1 -> "Nether"
            0 -> "Overworld"
            1 -> "End"
            else -> "Dimension $dimension"
        }
    }
}
