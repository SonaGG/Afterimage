package gg.sona.afterimage.protocol

data class SoundEffect(val name: String, val x: Int, val y: Int, val z: Int, val volume: Float, val pitch: Int) :
    ClientboundPacket {
    override val packetId: Int get() = ClientboundPlay.SOUND_EFFECT
}
