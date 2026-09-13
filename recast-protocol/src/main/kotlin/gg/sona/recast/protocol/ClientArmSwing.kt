package gg.sona.recast.protocol


object ClientArmSwing : ServerboundPacket {
    override val packetId: Int get() = ServerboundPlay.ANIMATION
}
