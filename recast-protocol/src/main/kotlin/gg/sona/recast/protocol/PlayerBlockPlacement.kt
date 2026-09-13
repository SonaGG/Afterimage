package gg.sona.recast.protocol


data class PlayerBlockPlacement(
    val position: Long,
    val face: Int,
    val heldItem: ItemStack,
    val cursorX: Int,
    val cursorY: Int,
    val cursorZ: Int,
) : ServerboundPacket {
    override val packetId: Int get() = ServerboundPlay.PLAYER_BLOCK_PLACEMENT
}
