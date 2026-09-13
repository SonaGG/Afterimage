package gg.sona.recast.mc

import gg.sona.recast.capture.packet.PacketSink
import gg.sona.recast.net.PacketDirection
import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelOutboundHandlerAdapter
import io.netty.channel.ChannelPromise
import net.minecraft.network.packet.Packet
import org.apache.logging.log4j.LogManager

class OutboundTap(private val sink: PacketSink) : ChannelOutboundHandlerAdapter() {
    override fun write(context: ChannelHandlerContext, message: Any, promise: ChannelPromise) {
        try {
            when (message) {
                is ByteBuf -> PacketFrames.capture(sink, PacketDirection.SERVERBOUND, message)
                is Packet<*> -> PacketFrames.capture(sink, PacketDirection.SERVERBOUND, message)
            }
        } catch (error: Throwable) {
            LOGGER.error("Recast outbound tap failed", error)
        }
        context.write(message, promise)
    }

    private companion object {
        val LOGGER = LogManager.getLogger("Recast")
    }
}
