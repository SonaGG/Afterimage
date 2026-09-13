package gg.sona.recast.protocol

data class BulkChunk(val chunkX: Int, val chunkZ: Int, val mask: Int, val data: ByteArray)
