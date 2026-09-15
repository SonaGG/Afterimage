package gg.sona.afterimage.protocol

data class SpawnObject(
    override val entityId: Int,
    val type: Int,
    val x: Int,
    val y: Int,
    val z: Int,
    val pitch: Byte,
    val yaw: Byte,
    val data: Int,
    val velocityX: Int,
    val velocityY: Int,
    val velocityZ: Int,
) : EntityPacket {
    override val packetId: Int get() = ClientboundPlay.SPAWN_OBJECT
}
