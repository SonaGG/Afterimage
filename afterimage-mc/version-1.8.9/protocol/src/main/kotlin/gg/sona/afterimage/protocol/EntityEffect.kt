package gg.sona.afterimage.protocol

data class EntityEffect(
    override val entityId: Int,
    val effectId: Int,
    val amplifier: Int,
    val duration: Int,
    val hideParticles: Boolean
) : EntityPacket {
    override val packetId: Int get() = ClientboundPlay.ENTITY_EFFECT
}
