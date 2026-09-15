package gg.sona.afterimage.protocol47

import gg.sona.afterimage.net.CapturedPacket
import gg.sona.afterimage.net.PacketDirection
import gg.sona.afterimage.protocol.AfterimageInternal
import gg.sona.afterimage.protocol.CameraFrame
import gg.sona.afterimage.protocol.ClientboundPlay
import gg.sona.afterimage.protocol.PacketCodec
import gg.sona.afterimage.protocol.Protocol
import gg.sona.afterimage.protocol.Respawn
import gg.sona.afterimage.protocol.SessionMark
import gg.sona.afterimage.protocol47.perspective.TransientPackets
import gg.sona.afterimage.protocol47.shadow.ShadowClient
import gg.sona.afterimage.replay.protocol.ReplayProtocol
import gg.sona.afterimage.replay.protocol.ReplayState
import gg.sona.afterimage.world.RecorderIdentity
import gg.sona.afterimage.world.camera.CameraSample

class Protocol47 : ReplayProtocol {
    override val version: Int get() = Protocol.VERSION

    override val minecraftVersion: String get() = Protocol.MINECRAFT_VERSION

    override fun createState(identity: RecorderIdentity): ReplayState = ShadowClient(identity)

    override fun isJoin(packet: CapturedPacket): Boolean =
        packet.direction == PacketDirection.CLIENTBOUND && packet.packetId == ClientboundPlay.JOIN_GAME

    override fun isTickMarker(packet: CapturedPacket): Boolean =
        packet.direction == PacketDirection.SERVERBOUND && packet.packetId == AfterimageInternal.LOCAL_TICK

    override fun respawnDimension(packet: CapturedPacket): Int? {
        if (packet.direction != PacketDirection.CLIENTBOUND || packet.packetId != ClientboundPlay.RESPAWN) return null
        return (PacketCodec.decode(packet) as? Respawn)?.dimension
    }

    override fun cameraSample(packet: CapturedPacket): CameraSample? {
        if (packet.direction != PacketDirection.SERVERBOUND) return null
        if (packet.packetId != AfterimageInternal.CAMERA_FRAME && packet.packetId != AfterimageInternal.CAMERA_FRAME_COMPACT) return null
        val frame = PacketCodec.decode(packet) as? CameraFrame ?: return null
        return CameraSample(packet.timestampNanos, frame.modelView, frame.fov, frame.position, frame.hand)
    }

    override fun isTransient(packet: CapturedPacket): Boolean = TransientPackets.isTransient(packet)

    override fun isVisualEvent(packet: CapturedPacket): Boolean = TransientPackets.isVisualEvent(packet)

    override fun marker(kind: Int, label: String, nanos: Long): CapturedPacket = PacketCodec.encode(SessionMark(kind, label), nanos)

    companion object {
        val INSTANCE = Protocol47()
    }
}
