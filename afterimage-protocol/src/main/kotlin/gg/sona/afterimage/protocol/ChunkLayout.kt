package gg.sona.afterimage.protocol

object ChunkLayout {
    const val SECTIONS = 16
    const val BLOCKS_PER_SECTION = 4096
    const val BLOCK_BYTES = BLOCKS_PER_SECTION * 2
    const val LIGHT_BYTES = BLOCKS_PER_SECTION / 2
    const val BIOME_BYTES = 256
    const val FULL_MASK = 0xFFFF

    fun sectionCount(mask: Int): Int = Integer.bitCount(mask and FULL_MASK)

    fun dataSize(mask: Int, skyLight: Boolean, groundUp: Boolean): Int {
        val sections = sectionCount(mask)
        var size = sections * (BLOCK_BYTES + LIGHT_BYTES)
        if (skyLight) size += sections * LIGHT_BYTES
        if (groundUp) size += BIOME_BYTES
        return size
    }

    fun blockIndex(x: Int, y: Int, z: Int): Int = ((y and 0xF) shl 8) or ((z and 0xF) shl 4) or (x and 0xF)

    fun hasSkyLight(dimension: Int): Boolean = dimension == 0
}
