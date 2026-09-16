package gg.sona.afterimage.mc189.replay

import gg.sona.afterimage.net.CapturedPacket
import gg.sona.afterimage.net.PacketDirection
import gg.sona.afterimage.protocol.*

object TransientPackets {
    private val PARTICLE_STATUSES = setOf(6, 7, 12, 13, 14, 15, 17, 18, 20)
    private const val FIRST_PARTICLE_EFFECT = 2000
    private const val LAST_PARTICLE_EFFECT = 2005

    fun isTransient(packet: CapturedPacket): Boolean {
        if (packet.direction != PacketDirection.CLIENTBOUND) return packet.packetId == AfterimageInternal.LOCAL_BLOCK_BREAK
        return when (packet.packetId) {
            ClientboundPlay.CHAT_MESSAGE,
            ClientboundPlay.TITLE,
            ClientboundPlay.COLLECT_ITEM,
            ClientboundPlay.EXPLOSION,
            ClientboundPlay.EFFECT,
            ClientboundPlay.SOUND_EFFECT,
            ClientboundPlay.PARTICLE,
            ClientboundPlay.ENTITY_STATUS,
                -> true

            ClientboundPlay.ANIMATION -> (PacketCodec.decode(packet) as? Animation)?.animation != Animation.LEAVE_BED
            else -> false
        }
    }

    fun isVisualEvent(packet: CapturedPacket): Boolean {
        if (packet.direction != PacketDirection.CLIENTBOUND) return packet.packetId == AfterimageInternal.LOCAL_BLOCK_BREAK
        return when (packet.packetId) {
            ClientboundPlay.PARTICLE, ClientboundPlay.EXPLOSION, ClientboundPlay.COLLECT_ITEM -> true
            ClientboundPlay.EFFECT -> (PacketCodec.decode(packet) as? WorldEffect)?.effectId in FIRST_PARTICLE_EFFECT..LAST_PARTICLE_EFFECT
            ClientboundPlay.ANIMATION -> when ((PacketCodec.decode(packet) as? Animation)?.animation) {
                Animation.CRITICAL_EFFECT, Animation.MAGIC_CRITICAL_EFFECT -> true
                else -> false
            }

            ClientboundPlay.ENTITY_STATUS -> (PacketCodec.decode(packet) as? EntityStatus)?.status in PARTICLE_STATUSES
            else -> false
        }
    }
}
