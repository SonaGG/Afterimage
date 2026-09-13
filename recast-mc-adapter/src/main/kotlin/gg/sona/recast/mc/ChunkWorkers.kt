package gg.sona.recast.mc

import gg.sona.recast.mc.mixin.ChunkRenderDispatcherAccessor
import gg.sona.recast.mc.mixin.WorldRendererAccessor
import net.minecraft.client.Minecraft
import net.minecraft.client.render.world.ChunkRenderWorker
import org.apache.logging.log4j.LogManager

class ChunkWorkers(private val minecraft: Minecraft) {
    private val logger = LogManager.getLogger("Recast")
    private var added = false

    fun ensure() {
        if (added) return
        val renderer = minecraft.worldRenderer as? WorldRendererAccessor ?: return
        val dispatcher = renderer.`recast$dispatcher`() ?: return
        val accessor = dispatcher as? ChunkRenderDispatcherAccessor ?: return
        added = true
        val buffers = accessor.`recast$availableBuffers`().size
        val workers = accessor.`recast$workers`()
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
        if (started > 0) logger.info("Recast started {} extra chunk compile threads ({} total)", started, workers.size)
    }
}
