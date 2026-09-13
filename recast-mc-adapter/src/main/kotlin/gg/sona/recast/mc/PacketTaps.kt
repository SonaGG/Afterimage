package gg.sona.recast.mc

import gg.sona.recast.capture.packet.PacketSink
import io.netty.channel.Channel

object PacketTaps {
    const val INBOUND = "recast_inbound_tap"
    const val OUTBOUND = "recast_outbound_tap"
    private const val DECODER = "decoder"
    private const val ENCODER = "encoder"
    private const val DECOMPRESS = "decompress"
    private const val COMPRESS = "compress"
    private const val PACKET_HANDLER = "packet_handler"

    fun install(channel: Channel, sink: PacketSink) {
        val pipeline = channel.pipeline()
        if (pipeline.get(INBOUND) == null) {
            val tap = InboundTap(sink)
            when {
                pipeline.get(DECODER) != null -> pipeline.addBefore(DECODER, INBOUND, tap)
                pipeline.get(PACKET_HANDLER) != null -> pipeline.addBefore(PACKET_HANDLER, INBOUND, tap)
                else -> pipeline.addFirst(INBOUND, tap)
            }
        }
        if (pipeline.get(OUTBOUND) == null) {
            val tap = OutboundTap(sink)
            when {
                pipeline.get(ENCODER) != null -> pipeline.addBefore(ENCODER, OUTBOUND, tap)
                pipeline.get(PACKET_HANDLER) != null -> pipeline.addBefore(PACKET_HANDLER, OUTBOUND, tap)
                else -> pipeline.addFirst(OUTBOUND, tap)
            }
        }
    }

    fun reposition(channel: Channel) {
        val pipeline = channel.pipeline()
        val names = pipeline.names()
        val inbound = pipeline.get(INBOUND)
        if (inbound != null && pipeline.get(DECODER) != null && pipeline.get(DECOMPRESS) != null && names.indexOf(
                INBOUND
            ) < names.indexOf(DECOMPRESS)
        ) {
            pipeline.remove(inbound)
            pipeline.addBefore(DECODER, INBOUND, inbound)
        }
        val outbound = pipeline.get(OUTBOUND)
        if (outbound != null && pipeline.get(ENCODER) != null && pipeline.get(COMPRESS) != null && names.indexOf(
                OUTBOUND
            ) < names.indexOf(COMPRESS)
        ) {
            pipeline.remove(outbound)
            pipeline.addBefore(ENCODER, OUTBOUND, outbound)
        }
    }

    fun remove(channel: Channel) {
        val pipeline = channel.pipeline()
        if (pipeline.get(INBOUND) != null) pipeline.remove(INBOUND)
        if (pipeline.get(OUTBOUND) != null) pipeline.remove(OUTBOUND)
    }
}
