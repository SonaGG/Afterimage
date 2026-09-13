package gg.sona.recast.protocol

data class MapData(
    val mapId: Int,
    val scale: Int,
    val icons: List<MapIcon>,
    val columns: Int,
    val rows: Int,
    val x: Int,
    val z: Int,
    val data: ByteArray,
) : ClientboundPacket {
    override val packetId: Int get() = ClientboundPlay.MAP
}
