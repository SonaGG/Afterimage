package gg.sona.afterimage.mc189.replay

import gg.sona.afterimage.mc189.compat.ArgentumCompat
import gg.sona.afterimage.mc189.mixin.ChunkRenderDispatcherAccessor
import gg.sona.afterimage.mc189.mixin.WorldRendererAccessor
import net.minecraft.client.Minecraft
import net.minecraft.client.render.world.ChunkRenderWorker
import org.apache.logging.log4j.LogManager

class ChunkWorkers(private val minecraft: Minecraft) {
    private val logger = LogManager.getLogger("Afterimage")
    private var added = false

    fun ensure() {
        if (added || ArgentumCompat.isLoaded) return
        val renderer = minecraft.worldRenderer as? WorldRendererAccessor ?: return
        val dispatcher = renderer.`afterimage$dispatcher`() ?: return
        val accessor = dispatcher as? ChunkRenderDispatcherAccessor ?: return
        added = true
        val buffers = accessor.`afterimage$availableBuffers`().size
        val workers = accessor.`afterimage$workers`()
        val target = minOf(buffers, Runtime.getRuntime().availableProcessors() - 1).coerceAtLeast(workers.size)
        var started = 0
        while (workers.size < target) {
            val worker = ChunkRenderWorker(dispatcher)
            val thread = Thread(worker, "Chunk Batcher ${workers.size}")
            thread.isDaemon = true
            thread.start()
            workers.add(worker)
            started++
        }
        if (started > 0) logger.info("Afterimage started {} extra chunk compile threads ({} total)", started, workers.size)
    }
}
