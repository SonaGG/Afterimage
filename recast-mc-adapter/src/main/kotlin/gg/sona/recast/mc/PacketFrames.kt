package gg.sona.recast.mc

import gg.sona.recast.capture.packet.PacketSink
import gg.sona.recast.net.PacketDirection
import gg.sona.recast.net.PacketReader
import gg.sona.recast.net.VarInts
import gg.sona.recast.protocol.ClientboundPlay
import gg.sona.recast.protocol.PacketCodec
import gg.sona.recast.protocol.RecastChannel
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import net.minecraft.network.NetworkProtocol
import net.minecraft.network.PacketByteBuf
import net.minecraft.network.PacketFlow
import net.minecraft.network.packet.Packet
import net.minecraft.network.packet.c2s.play.CustomPayloadC2SPacket
import net.minecraft.network.packet.s2c.play.CustomPayloadS2CPacket
import org.apache.logging.log4j.LogManager

object PacketFrames {
    private val logger = LogManager.getLogger("Recast")
    private val warned = HashSet<Class<*>>()

    fun capture(sink: PacketSink, direction: PacketDirection, frame: ByteBuf) {
        val length = frame.readableBytes()
        if (length <= 0) return
        val bytes = ByteArray(length)
        frame.getBytes(frame.readerIndex(), bytes)
        val packed = VarInts.read(bytes, 0, bytes.size)
        val idLength = VarInts.length(packed)
        val id = VarInts.value(packed)
        sink.offer(direction, id, bytes, idLength, bytes.size - idLength)
        if (direction == PacketDirection.CLIENTBOUND && id == ClientboundPlay.PLUGIN_MESSAGE) supplementary(
            sink,
            bytes,
            idLength
        )
    }

    private fun supplementary(sink: PacketSink, bytes: ByteArray, offset: Int) {
        try {
            val reader = PacketReader(bytes, offset, bytes.size)
            val channel = reader.readString(20)
            if (channel != RecastChannel.NAME) return
            for (packet in RecastChannel.translate(reader.readRemaining())) {
                val encoded = PacketCodec.encode(packet, 0L)
                sink.offer(PacketDirection.CLIENTBOUND, encoded.packetId, encoded.payload)
            }
        } catch (error: Throwable) {
            if (warned.add(RecastChannelMarker::class.java)) logger.warn(
                "Recast could not translate a recast:v1 message",
                error
            )
        }
    }

    private object RecastChannelMarker

    fun capture(sink: PacketSink, direction: PacketDirection, packet: Packet<*>) {
        val flow = if (direction == PacketDirection.CLIENTBOUND) PacketFlow.CLIENTBOUND else PacketFlow.SERVERBOUND
        val id = NetworkProtocol.PLAY.getPacketId(flow, packet) ?: return
        val payload = embeddedPayload(packet)
        val payloadReaderIndex = payload?.readerIndex() ?: 0
        val buffer = PacketByteBuf(Unpooled.buffer(256))
        try {
            packet.write(buffer)
            val bytes = ByteArray(buffer.readableBytes())
            buffer.getBytes(buffer.readerIndex(), bytes)
            sink.offer(direction, id, bytes)
        } catch (error: Throwable) {
            if (warned.add(packet.javaClass)) logger.warn(
                "Recast could not encode local packet {}",
                packet.javaClass.simpleName,
                error
            )
        } finally {
            payload?.readerIndex(payloadReaderIndex)
            buffer.release()
        }
    }

    private fun embeddedPayload(packet: Packet<*>): ByteBuf? = when (packet) {
        is CustomPayloadS2CPacket -> packet.data
        is CustomPayloadC2SPacket -> packet.data
        else -> null
    }
}
