package gg.sona.recast.format


class ChunkRef(
    val chunkX: Int,
    val chunkZ: Int,
    val mask: Int,
    val skyLight: Boolean,
    val bulk: Boolean,
    val hash: Long
)
