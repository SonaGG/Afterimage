package gg.sona.recast.replay.state.shadow

import gg.sona.recast.protocol.BulkChunk
import gg.sona.recast.protocol.ChunkData
import gg.sona.recast.protocol.ChunkLayout
import gg.sona.recast.replay.state.ContentHash

class ShadowChunk(val chunkX: Int, val chunkZ: Int) {
    val sections = arrayOfNulls<ShadowSection>(ChunkLayout.SECTIONS)
    var biomes: ByteArray? = null
    var loadedAtNanos: Long = 0L

    val sectionMask: Int
        get() {
            var mask = 0
            for (index in sections.indices) if (sections[index] != null) mask = mask or (1 shl index)
            return mask
        }

    fun load(mask: Int, data: ByteArray, skyLight: Boolean, groundUp: Boolean) {
        var offset = 0
        for (index in 0 until ChunkLayout.SECTIONS) {
            if (mask and (1 shl index) == 0) {
                if (groundUp) sections[index] = null
                continue
            }
            val section = sections[index] ?: ShadowSection().also { sections[index] = it }
            section.invalidate()
            System.arraycopy(data, offset, section.blocks, 0, ChunkLayout.BLOCK_BYTES)
            offset += ChunkLayout.BLOCK_BYTES
        }
        for (index in 0 until ChunkLayout.SECTIONS) {
            if (mask and (1 shl index) == 0) continue
            System.arraycopy(data, offset, sections[index]!!.blockLight, 0, ChunkLayout.LIGHT_BYTES)
            offset += ChunkLayout.LIGHT_BYTES
        }
        if (skyLight) {
            for (index in 0 until ChunkLayout.SECTIONS) {
                if (mask and (1 shl index) == 0) continue
                val section = sections[index]!!
                val target = section.skyLight ?: ByteArray(ChunkLayout.LIGHT_BYTES).also { section.skyLight = it }
                System.arraycopy(data, offset, target, 0, ChunkLayout.LIGHT_BYTES)
                offset += ChunkLayout.LIGHT_BYTES
            }
        }
        if (groundUp && offset + ChunkLayout.BIOME_BYTES <= data.size) {
            val target = biomes ?: ByteArray(ChunkLayout.BIOME_BYTES).also { biomes = it }
            System.arraycopy(data, offset, target, 0, ChunkLayout.BIOME_BYTES)
        }
    }

    fun blockState(x: Int, y: Int, z: Int): Int {
        if (y !in 0..255) return 0
        val section = sections[y shr 4] ?: return 0
        return section.blockState(ChunkLayout.blockIndex(x, y, z))
    }

    fun setBlockState(x: Int, y: Int, z: Int, state: Int, skyLight: Boolean): Int {
        if (y !in 0..255) return 0
        val sectionIndex = y shr 4
        var section = sections[sectionIndex]
        if (section == null) {
            if (state == 0) return 0
            section = ShadowSection()
            if (skyLight) section.skyLight = ByteArray(ChunkLayout.LIGHT_BYTES) { 0xFF.toByte() }
            sections[sectionIndex] = section
        }
        val index = ChunkLayout.blockIndex(x, y, z)
        val previous = section.blockState(index)
        section.setBlockState(index, state)
        return previous
    }

    fun contentHash(): Long {
        var hash = ContentHash.seed()
        for (index in sections.indices) {
            val section = sections[index] ?: continue
            hash = ContentHash.mix(hash, index.toLong())
            hash = ContentHash.mix(hash, section.contentHash())
        }
        biomes?.let { hash = ContentHash.mix(hash, it) }
        return hash
    }

    fun encodedSize(skyLight: Boolean): Int = ChunkLayout.dataSize(sectionMask, skyLight, groundUp = true)

    fun encodeBulk(skyLight: Boolean): BulkChunk {
        val encoded = encode(skyLight)
        return BulkChunk(chunkX, chunkZ, encoded.mask, encoded.data)
    }

    fun encode(skyLight: Boolean): ChunkData {
        val mask = sectionMask
        val data = ByteArray(ChunkLayout.dataSize(mask, skyLight, groundUp = true))
        var offset = 0
        for (section in sections) {
            if (section == null) continue
            System.arraycopy(section.blocks, 0, data, offset, ChunkLayout.BLOCK_BYTES)
            offset += ChunkLayout.BLOCK_BYTES
        }
        for (section in sections) {
            if (section == null) continue
            System.arraycopy(section.blockLight, 0, data, offset, ChunkLayout.LIGHT_BYTES)
            offset += ChunkLayout.LIGHT_BYTES
        }
        if (skyLight) {
            for (section in sections) {
                if (section == null) continue
                val light = section.skyLight
                if (light != null) System.arraycopy(light, 0, data, offset, ChunkLayout.LIGHT_BYTES)
                else data.fill(0xFF.toByte(), offset, offset + ChunkLayout.LIGHT_BYTES)
                offset += ChunkLayout.LIGHT_BYTES
            }
        }
        val biomes = biomes
        if (biomes != null) System.arraycopy(biomes, 0, data, offset, ChunkLayout.BIOME_BYTES)
        return ChunkData(chunkX, chunkZ, true, mask, data)
    }
}
