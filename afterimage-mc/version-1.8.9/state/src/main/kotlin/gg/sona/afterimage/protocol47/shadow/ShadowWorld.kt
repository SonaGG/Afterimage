package gg.sona.afterimage.protocol47.shadow

import gg.sona.afterimage.core.collect.ChunkKeys
import gg.sona.afterimage.core.collect.LongObjectMap
import gg.sona.afterimage.net.PackedPosition

class ShadowWorld {
    val chunks = LongObjectMap<ShadowChunk>(256)
    val blockEntities = LongObjectMap<BlockEntityRecord>(64)
    val signs = LongObjectMap<SignRecord>(32)
    val maps = HashMap<Int, MapCanvas>()
    val border = BorderState()

    var dimension: Int = 0
    var difficulty: Int = 0
    var levelType: String = "default"
    var maxPlayers: Int = 20
    var worldAge: Long = 0L
    var timeOfDay: Long = 0L
    var timeUpdatedAtNanos: Long = 0L
    var hasTime: Boolean = false
    var spawnPosition: Long = 0L
    var hasSpawn: Boolean = false
    var raining: Boolean = false
    var rainStrength: Float = 0f
    var thunderStrength: Float = 0f

    val hasSkyLight: Boolean get() = dimension != -1 && dimension != 1

    fun chunk(chunkX: Int, chunkZ: Int): ShadowChunk? = chunks[ChunkKeys.of(chunkX, chunkZ)]

    fun loadChunk(
        chunkX: Int,
        chunkZ: Int,
        mask: Int,
        data: ByteArray,
        skyLight: Boolean,
        groundUp: Boolean,
        nanos: Long
    ) {
        val key = ChunkKeys.of(chunkX, chunkZ)
        val chunk = chunks.getOrPut(key) { ShadowChunk(chunkX, chunkZ).also { it.loadedAtNanos = nanos } }
        if (groundUp) forgetBlockEntitiesIn(chunkX, chunkZ)
        chunk.load(mask, data, skyLight, groundUp)
    }

    fun unloadChunk(chunkX: Int, chunkZ: Int) {
        chunks.remove(ChunkKeys.of(chunkX, chunkZ))
        forgetBlockEntitiesIn(chunkX, chunkZ)
    }

    fun blockState(x: Int, y: Int, z: Int): Int = chunk(x shr 4, z shr 4)?.blockState(x, y, z) ?: 0

    fun setBlockState(x: Int, y: Int, z: Int, state: Int) {
        val chunk = chunk(x shr 4, z shr 4) ?: return
        val previous = chunk.setBlockState(x, y, z, state, hasSkyLight)
        if (previous shr 4 != state shr 4) {
            val position = PackedPosition.pack(x, y, z)
            blockEntities.remove(position)
            signs.remove(position)
        }
    }

    fun clear() {
        chunks.clear()
        blockEntities.clear()
        signs.clear()
        hasTime = false
        hasSpawn = false
        raining = false
        rainStrength = 0f
        thunderStrength = 0f
    }

    private fun forgetBlockEntitiesIn(chunkX: Int, chunkZ: Int) {
        if (!blockEntities.isEmpty()) {
            for (key in blockEntities.keys()) {
                if (PackedPosition.x(key) shr 4 == chunkX && PackedPosition.z(key) shr 4 == chunkZ) blockEntities.remove(
                    key
                )
            }
        }
        if (!signs.isEmpty()) {
            for (key in signs.keys()) {
                if (PackedPosition.x(key) shr 4 == chunkX && PackedPosition.z(key) shr 4 == chunkZ) signs.remove(key)
            }
        }
    }
}
