package gg.sona.afterimage.protocol

data class Explosion(
    val x: Float,
    val y: Float,
    val z: Float,
    val radius: Float,
    val affectedBlocks: List<MetaBlockPos>,
    val motionX: Float,
    val motionY: Float,
    val motionZ: Float,
) : ClientboundPacket {
    override val packetId: Int get() = ClientboundPlay.EXPLOSION
}
