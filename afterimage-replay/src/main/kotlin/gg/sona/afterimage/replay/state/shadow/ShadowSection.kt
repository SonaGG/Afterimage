package gg.sona.afterimage.replay.state.shadow

import gg.sona.afterimage.protocol.ChunkLayout
import gg.sona.afterimage.replay.state.ContentHash

class ShadowSection {
    val blocks = ByteArray(ChunkLayout.BLOCK_BYTES)
    val blockLight = ByteArray(ChunkLayout.LIGHT_BYTES)
    var skyLight: ByteArray? = null
    private var cachedHash = 0L
    private var hashValid = false

    fun invalidate() {
        hashValid = false
    }

    fun contentHash(): Long {
        if (hashValid) return cachedHash
        var hash = ContentHash.seed()
        hash = ContentHash.mix(hash, blocks)
        hash = ContentHash.mix(hash, blockLight)
        skyLight?.let { hash = ContentHash.mix(hash, it) }
        cachedHash = hash
        hashValid = true
        return hash
    }

    fun blockState(index: Int): Int {
        val offset = index shl 1
        return (blocks[offset].toInt() and 0xFF) or ((blocks[offset + 1].toInt() and 0xFF) shl 8)
    }

    fun setBlockState(index: Int, state: Int) {
        val offset = index shl 1
        blocks[offset] = state.toByte()
        blocks[offset + 1] = (state shr 8).toByte()
        hashValid = false
    }

    fun isEmpty(): Boolean {
        for (byte in blocks) if (byte.toInt() != 0) return false
        return true
    }
}
