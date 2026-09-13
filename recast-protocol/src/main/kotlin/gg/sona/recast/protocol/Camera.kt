package gg.sona.recast.protocol

data class Camera(val cameraEntityId: Int) : ClientboundPacket {
    override val packetId: Int get() = ClientboundPlay.CAMERA
}
