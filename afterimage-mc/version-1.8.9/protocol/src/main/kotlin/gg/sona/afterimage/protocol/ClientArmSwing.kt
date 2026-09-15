package gg.sona.afterimage.protocol


object ClientArmSwing : ServerboundPacket {
    override val packetId: Int get() = ServerboundPlay.ANIMATION
}
