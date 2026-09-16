package gg.sona.afterimage.mc263.state

import net.minecraft.core.Holder
import net.minecraft.network.protocol.game.ClientboundUpdateMobEffectPacket
import net.minecraft.world.effect.MobEffect
import net.minecraft.world.effect.MobEffectInstance

class ShadowEffect(val packet: ClientboundUpdateMobEffectPacket, val appliedAtNanos: Long) {
    val effect: Holder<MobEffect> get() = packet.effect
    val amplifier: Int get() = packet.effectAmplifier

    fun remainingTicks(nowNanos: Long): Int {
        if (packet.effectDurationTicks < 0) return Int.MAX_VALUE
        val elapsed = ((nowNanos - appliedAtNanos) / 50_000_000L).toInt()
        return packet.effectDurationTicks - elapsed
    }

    fun packetAt(entityId: Int, nowNanos: Long): ClientboundUpdateMobEffectPacket? {
        val remaining = remainingTicks(nowNanos)
        if (remaining <= 0) return null
        val duration = if (packet.effectDurationTicks < 0) -1 else remaining
        val instance = MobEffectInstance(packet.effect, duration, packet.effectAmplifier, packet.isEffectAmbient, packet.isEffectVisible, packet.effectShowsIcon())
        return ClientboundUpdateMobEffectPacket(entityId, instance, false)
    }

    fun same(other: ShadowEffect?): Boolean =
        other != null && other.effect == effect && other.amplifier == amplifier && other.packet.effectDurationTicks == packet.effectDurationTicks && other.appliedAtNanos == appliedAtNanos
}
