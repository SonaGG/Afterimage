package gg.sona.afterimage.mc26.state

import net.minecraft.core.BlockPos
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.PalettedContainer
import net.minecraft.world.level.chunk.PalettedContainerFactory

class ShadowChunk26(val chunk: Retained<ClientboundLevelChunkWithLightPacket>, val loadedAtNanos: Long) {
    val updates = ArrayList<Retained<*>>()
    val blockEntities = LinkedHashMap<BlockPos, Retained<ClientboundBlockEntityDataPacket>>()
    var updateHash: Long = 0L
    private var sections: Array<PalettedContainer<BlockState>?>? = null
    private var minSectionY: Int = 0

    fun update(packet: Retained<*>) {
        updates += packet
        updateHash = updateHash * 31 + packet.hash
        if (sections != null) applyUpdate(packet.packet)
    }

    fun same(other: ShadowChunk26): Boolean =
        chunk.hash == other.chunk.hash && updateHash == other.updateHash && blockEntities.size == other.blockEntities.size &&
                blockEntities.all { (pos, packet) -> other.blockEntities[pos]?.hash == packet.hash }

    fun emit(out: MutableList<Packet<*>>) {
        out += chunk.packet
        for (update in updates) out += update.packet
        for (entity in blockEntities.values) out += entity.packet
    }

    fun blockState(x: Int, y: Int, z: Int, factory: PalettedContainerFactory, minSectionY: Int, sectionCount: Int): Int {
        val decoded = sections ?: decode(factory, minSectionY, sectionCount) ?: return 0
        val section = decoded.getOrNull((y shr 4) - this.minSectionY) ?: return 0
        return Block.getId(section.get(x and 15, y and 15, z and 15))
    }

    fun setBlockState(x: Int, y: Int, z: Int, state: BlockState) {
        val section = sections?.getOrNull((y shr 4) - minSectionY) ?: return
        section.set(x and 15, y and 15, z and 15, state)
    }

    private fun decode(factory: PalettedContainerFactory, minSectionY: Int, sectionCount: Int): Array<PalettedContainer<BlockState>?>? {
        val result = arrayOfNulls<PalettedContainer<BlockState>>(sectionCount)
        val buffer = chunk.packet.chunkData().readBuffer
        try {
            for (index in 0 until sectionCount) {
                buffer.readShort()
                buffer.readShort()
                val states = factory.createForBlockStates()
                states.read(buffer)
                factory.createForBiomes().read(buffer)
                result[index] = states
            }
        } catch (error: Exception) {
            return null
        }
        this.minSectionY = minSectionY
        sections = result
        for (update in updates) applyUpdate(update.packet)
        return result
    }

    private fun applyUpdate(packet: Packet<*>) {
        when (packet) {
            is ClientboundBlockUpdatePacket -> setBlockState(packet.pos.x, packet.pos.y, packet.pos.z, packet.blockState)
            is ClientboundSectionBlocksUpdatePacket -> packet.runUpdates { pos, state -> setBlockState(pos.x, pos.y, pos.z, state) }
            else -> Unit
        }
    }
}