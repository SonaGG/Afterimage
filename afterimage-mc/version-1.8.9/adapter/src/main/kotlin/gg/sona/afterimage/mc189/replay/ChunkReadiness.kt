package gg.sona.afterimage.mc189.replay

import gg.sona.afterimage.mc189.compat.ArgentumCompat
import gg.sona.afterimage.mc189.mixin.ChunkRenderDispatcherAccessor
import gg.sona.afterimage.mc189.mixin.WorldRendererAccessor
import net.minecraft.client.Minecraft

class ChunkReadiness(private val minecraft: Minecraft) {
    private var maxBuffers = 0

    fun calibrate() {
        if (ArgentumCompat.handlesTerrain) return
        val renderer = minecraft.worldRenderer as? WorldRendererAccessor ?: return
        val dispatcher = renderer.`afterimage$dispatcher`() as? ChunkRenderDispatcherAccessor ?: return
        maxBuffers = maxOf(maxBuffers, dispatcher.`afterimage$availableBuffers`().size, dispatcher.`afterimage$workers`().size)
    }

    fun settled(): Boolean {
        if (ArgentumCompat.handlesTerrain) return ArgentumCompat.terrainSettled(minecraft.worldRenderer)
        val renderer = minecraft.worldRenderer as? WorldRendererAccessor ?: return true
        val dispatcher = renderer.`afterimage$dispatcher`() as? ChunkRenderDispatcherAccessor ?: return true
        val buffers = dispatcher.`afterimage$availableBuffers`().size
        if (buffers > maxBuffers) maxBuffers = buffers
        return !renderer.`afterimage$viewChanged`() &&
                renderer.`afterimage$dirtyChunks`().isEmpty() &&
                dispatcher.`afterimage$pendingTasks`().isEmpty() &&
                dispatcher.`afterimage$pendingUploads`().isEmpty() &&
                buffers >= maxBuffers
    }

    fun pendingDescription(): String {
        if (ArgentumCompat.handlesTerrain) return ArgentumCompat.pendingTerrainDescription(minecraft.worldRenderer)
        val renderer = minecraft.worldRenderer as? WorldRendererAccessor ?: return ""
        val dispatcher = renderer.`afterimage$dispatcher`() as? ChunkRenderDispatcherAccessor ?: return ""
        val dirty = renderer.`afterimage$dirtyChunks`().size
        val compiling =
            dispatcher.`afterimage$pendingTasks`().size + (maxBuffers - dispatcher.`afterimage$availableBuffers`().size).coerceAtLeast(
                0
            )
        val uploads = dispatcher.`afterimage$pendingUploads`().size
        val graph = if (renderer.`afterimage$viewChanged`()) " (visibility pass)" else ""
        return "$dirty dirty, $compiling compiling, $uploads uploads$graph"
    }
}
