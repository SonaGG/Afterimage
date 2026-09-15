package gg.sona.afterimage.mc26.replay

import gg.sona.afterimage.mc26.mixin.LevelRendererAccessor
import gg.sona.afterimage.mc26.mixin.SectionOcclusionGraphAccessor
import net.minecraft.client.Minecraft

class ChunkReadiness26(private val minecraft: Minecraft) {
    private var maxBuffers = 0

    fun calibrate() {
        val dispatcher = (minecraft.levelRenderer as LevelRendererAccessor).afterimage_sectionRenderDispatcher() ?: return
        maxBuffers = maxOf(maxBuffers, dispatcher.freeBufferCount)
    }

    fun settled(): Boolean {
        val renderer = minecraft.levelRenderer
        val dispatcher = (renderer as LevelRendererAccessor).afterimage_sectionRenderDispatcher() ?: return true
        val buffers = dispatcher.freeBufferCount
        if (buffers > maxBuffers) maxBuffers = buffers
        if (!dispatcher.isQueueEmpty || buffers < maxBuffers) return false
        val graph = renderer.sectionOcclusionGraph() as SectionOcclusionGraphAccessor
        if (graph.afterimage_needsFullUpdate()) return false
        val task = graph.afterimage_fullUpdateTask()
        if (task != null && !task.isDone) return false
        return !graph.afterimage_needsFrustumUpdate().get()
    }

    fun pendingDescription(): String {
        val dispatcher = (minecraft.levelRenderer as LevelRendererAccessor).afterimage_sectionRenderDispatcher() ?: return ""
        val compiling = dispatcher.compileQueueSize + (maxBuffers - dispatcher.freeBufferCount).coerceAtLeast(0)
        return "$compiling compiling"
    }
}
