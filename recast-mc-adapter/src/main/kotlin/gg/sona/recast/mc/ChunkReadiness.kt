package gg.sona.recast.mc

import gg.sona.recast.mc.compat.ArgentumCompat
import gg.sona.recast.mc.mixin.ChunkRenderDispatcherAccessor
import gg.sona.recast.mc.mixin.WorldRendererAccessor
import net.minecraft.client.Minecraft

class ChunkReadiness(private val minecraft: Minecraft) {
    private var maxBuffers = 0

    fun calibrate() {
        if (ArgentumCompat.handlesTerrain) return
        val renderer = minecraft.worldRenderer as? WorldRendererAccessor ?: return
        val dispatcher = renderer.`recast$dispatcher`() as? ChunkRenderDispatcherAccessor ?: return
        maxBuffers = maxOf(maxBuffers, dispatcher.`recast$availableBuffers`().size, dispatcher.`recast$workers`().size)
    }

    fun settled(): Boolean {
        if (ArgentumCompat.handlesTerrain) return ArgentumCompat.terrainSettled(minecraft.worldRenderer)
        val renderer = minecraft.worldRenderer as? WorldRendererAccessor ?: return true
        val dispatcher = renderer.`recast$dispatcher`() as? ChunkRenderDispatcherAccessor ?: return true
        val buffers = dispatcher.`recast$availableBuffers`().size
        if (buffers > maxBuffers) maxBuffers = buffers
        return !renderer.`recast$viewChanged`() &&
                renderer.`recast$dirtyChunks`().isEmpty() &&
                dispatcher.`recast$pendingTasks`().isEmpty() &&
                dispatcher.`recast$pendingUploads`().isEmpty() &&
                buffers >= maxBuffers
    }

    fun pendingDescription(): String {
        if (ArgentumCompat.handlesTerrain) return ArgentumCompat.pendingTerrainDescription(minecraft.worldRenderer)
        val renderer = minecraft.worldRenderer as? WorldRendererAccessor ?: return ""
        val dispatcher = renderer.`recast$dispatcher`() as? ChunkRenderDispatcherAccessor ?: return ""
        val dirty = renderer.`recast$dirtyChunks`().size
        val compiling =
            dispatcher.`recast$pendingTasks`().size + (maxBuffers - dispatcher.`recast$availableBuffers`().size).coerceAtLeast(
                0
            )
        val uploads = dispatcher.`recast$pendingUploads`().size
        val graph = if (renderer.`recast$viewChanged`()) " (visibility pass)" else ""
        return "$dirty dirty, $compiling compiling, $uploads uploads$graph"
    }
}
