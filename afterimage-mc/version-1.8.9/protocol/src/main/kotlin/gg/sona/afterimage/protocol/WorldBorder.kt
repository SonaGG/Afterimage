package gg.sona.afterimage.protocol

data class WorldBorder(
    val action: Int,
    val radius: Double,
    val oldRadius: Double,
    val newRadius: Double,
    val speed: Long,
    val centerX: Double,
    val centerZ: Double,
    val portalTeleportBoundary: Int,
    val warningTime: Int,
    val warningBlocks: Int,
) : ClientboundPacket {
    override val packetId: Int get() = ClientboundPlay.WORLD_BORDER

    companion object {
        const val SET_SIZE = 0
        const val LERP_SIZE = 1
        const val SET_CENTER = 2
        const val INITIALIZE = 3
        const val SET_WARNING_TIME = 4
        const val SET_WARNING_BLOCKS = 5
    }
}
