package gg.sona.recast.protocol

import gg.sona.recast.net.nbt.NbtCompound

data class UpdateEntityNbt(override val entityId: Int, val nbt: NbtCompound?) : EntityPacket {
    override val packetId: Int get() = ClientboundPlay.UPDATE_ENTITY_NBT
}
