package gg.sona.afterimage.protocol

import gg.sona.afterimage.net.nbt.NbtCompound

data class UpdateBlockEntity(val position: Long, val action: Int, val nbt: NbtCompound?) : ClientboundPacket {
    override val packetId: Int get() = ClientboundPlay.UPDATE_BLOCK_ENTITY
}
