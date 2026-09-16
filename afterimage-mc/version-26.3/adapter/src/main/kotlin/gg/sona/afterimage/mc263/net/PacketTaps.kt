package gg.sona.afterimage.mc263.net

import gg.sona.afterimage.capture.packet.PacketSink
import gg.sona.afterimage.mc263.mixin.PacketDecoderAccessor
import gg.sona.afterimage.mc263.mixin.PacketEncoderAccessor
import gg.sona.afterimage.net.PacketDirection
import gg.sona.afterimage.net.VarInts
import io.netty.buffer.ByteBuf
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.ChannelOutboundHandlerAdapter
import io.netty.channel.ChannelPromise
import net.minecraft.network.ConnectionProtocol
import org.slf4j.LoggerFactory

object PacketTaps {
    const val INBOUND = "afterimage_inbound_tap"
    const val OUTBOUND = "afterimage_outbound_tap"
    private const val DECODER = "decoder"
    private const val ENCODER = "encoder"
    private const val INBOUND_CONFIG = "inbound_config"
    private const val OUTBOUND_CONFIG = "outbound_config"
    private const val PACKET_HANDLER = "packet_handler"
    private val logger = LoggerFactory.getLogger("Afterimage")

    fun install(channel: Channel, sink: PacketSink) {
        val pipeline = channel.pipeline()
        if (pipeline.get(INBOUND) == null) {
            val tap = InboundTap(sink)
            when {
                pipeline.get(DECODER) != null -> pipeline.addBefore(DECODER, INBOUND, tap)
                pipeline.get(INBOUND_CONFIG) != null -> pipeline.addBefore(INBOUND_CONFIG, INBOUND, tap)
                pipeline.get(PACKET_HANDLER) != null -> pipeline.addBefore(PACKET_HANDLER, INBOUND, tap)
                else -> pipeline.addFirst(INBOUND, tap)
            }
        }
        if (pipeline.get(OUTBOUND) == null) {
            val tap = OutboundTap(sink)
            when {
                pipeline.get(ENCODER) != null -> pipeline.addBefore(ENCODER, OUTBOUND, tap)
                pipeline.get(OUTBOUND_CONFIG) != null -> pipeline.addBefore(OUTBOUND_CONFIG, OUTBOUND, tap)
                pipeline.get(PACKET_HANDLER) != null -> pipeline.addBefore(PACKET_HANDLER, OUTBOUND, tap)
                else -> pipeline.addFirst(OUTBOUND, tap)
            }
        }
    }

    fun remove(channel: Channel) {
        val pipeline = channel.pipeline()
        if (pipeline.get(INBOUND) != null) pipeline.remove(INBOUND)
        if (pipeline.get(OUTBOUND) != null) pipeline.remove(OUTBOUND)
    }

    fun capture(sink: PacketSink, direction: PacketDirection, frame: ByteBuf, configuration: Boolean) {
        val length = frame.readableBytes()
        if (length <= 0) return
        val bytes = ByteArray(length)
        frame.getBytes(frame.readerIndex(), bytes)
        val packed = VarInts.read(bytes, 0, bytes.size)
        val idLength = VarInts.length(packed)
        val id = VarInts.value(packed) or (if (configuration) PacketIds.CONFIGURATION_FLAG else 0)
        sink.offer(direction, id, bytes, idLength, bytes.size - idLength)
    }

    private fun inboundPhase(context: ChannelHandlerContext): ConnectionProtocol? =
        (context.pipeline().get(DECODER) as? PacketDecoderAccessor)?.afterimage_protocolInfo()?.id()

    private fun outboundPhase(context: ChannelHandlerContext): ConnectionProtocol? =
        (context.pipeline().get(ENCODER) as? PacketEncoderAccessor)?.afterimage_protocolInfo()?.id()

    private fun recorded(phase: ConnectionProtocol?): Boolean = phase == ConnectionProtocol.PLAY || phase == ConnectionProtocol.CONFIGURATION

    class InboundTap(private val sink: PacketSink) : ChannelInboundHandlerAdapter() {
        override fun channelRead(context: ChannelHandlerContext, message: Any) {
            try {
                if (message is ByteBuf) {
                    val phase = inboundPhase(context)
                    if (recorded(phase)) capture(sink, PacketDirection.CLIENTBOUND, message, phase == ConnectionProtocol.CONFIGURATION)
                }
            } catch (error: Throwable) {
                logger.error("Afterimage inbound tap failed", error)
            }
            context.fireChannelRead(message)
        }
    }

    class OutboundTap(private val sink: PacketSink) : ChannelOutboundHandlerAdapter() {
        override fun write(context: ChannelHandlerContext, message: Any, promise: ChannelPromise) {
            try {
                if (message is ByteBuf) {
                    val phase = outboundPhase(context)
                    if (recorded(phase)) capture(sink, PacketDirection.SERVERBOUND, message, phase == ConnectionProtocol.CONFIGURATION)
                }
            } catch (error: Throwable) {
                logger.error("Afterimage outbound tap failed", error)
            }
            context.write(message, promise)
        }
    }
}
