package gg.sona.afterimage.core.collect

object ChunkKeys {
    fun of(chunkX: Int, chunkZ: Int): Long = (chunkX.toLong() shl 32) or (chunkZ.toLong() and 0xFFFFFFFFL)

    fun x(key: Long): Int = (key shr 32).toInt()
    fun z(key: Long): Int = key.toInt()
}
