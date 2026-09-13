package gg.sona.recast.format


class ChunkBlobEntry(
    val chunkX: Int,
    val chunkZ: Int,
    val mask: Int,
    val skyLight: Boolean,
    val data: ByteArray,
    val bulk: Boolean
)
