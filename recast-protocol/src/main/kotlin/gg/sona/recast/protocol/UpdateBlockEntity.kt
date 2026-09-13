package gg.sona.recast.protocol

import gg.sona.recast.net.nbt.NbtCompound

data class UpdateBlockEntity(val position: Long, val action: Int, val nbt: NbtCompound?) : ClientboundPacket {
    override val packetId: Int get() = ClientboundPlay.UPDATE_BLOCK_ENTITY
}
