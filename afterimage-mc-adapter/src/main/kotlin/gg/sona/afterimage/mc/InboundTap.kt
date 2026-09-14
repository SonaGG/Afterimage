package gg.sona.afterimage.mc

import gg.sona.afterimage.capture.packet.PacketSink
import gg.sona.afterimage.net.PacketDirection
import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import net.minecraft.network.packet.Packet
import org.apache.logging.log4j.LogManager

class InboundTap(private val sink: PacketSink) : ChannelInboundHandlerAdapter() {
    override fun channelRead(context: ChannelHandlerContext, message: Any) {
        try {
            when (message) {
                is ByteBuf -> PacketFrames.capture(sink, PacketDirection.CLIENTBOUND, message)
                is Packet<*> -> PacketFrames.capture(sink, PacketDirection.CLIENTBOUND, message)
            }
        } catch (error: Throwable) {
            LOGGER.error("Afterimage inbound tap failed", error)
        }
        context.fireChannelRead(message)
    }

    private companion object {
        val LOGGER = LogManager.getLogger("Afterimage")
    }
}
