package gg.sona.afterimage.protocol

import gg.sona.afterimage.net.nbt.NbtCompound

data class UpdateEntityNbt(override val entityId: Int, val nbt: NbtCompound?) : EntityPacket {
    override val packetId: Int get() = ClientboundPlay.UPDATE_ENTITY_NBT
}
