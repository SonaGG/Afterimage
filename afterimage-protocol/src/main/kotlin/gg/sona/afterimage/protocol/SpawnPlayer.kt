package gg.sona.afterimage.protocol

import java.util.*

data class SpawnPlayer(
    override val entityId: Int,
    val uuid: UUID,
    val x: Int,
    val y: Int,
    val z: Int,
    val yaw: Byte,
    val pitch: Byte,
    val currentItem: Int,
    val metadata: List<MetadataEntry>,
) : EntityPacket {
    override val packetId: Int get() = ClientboundPlay.SPAWN_PLAYER
}
