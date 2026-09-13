package gg.sona.recast.protocol

data class EntityEquipment(override val entityId: Int, val slot: Int, val item: ItemStack) : EntityPacket {
    override val packetId: Int get() = ClientboundPlay.ENTITY_EQUIPMENT
}
