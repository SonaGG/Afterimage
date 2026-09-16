package gg.sona.afterimage.mc189.compat

import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.render.world.WorldRenderer
import org.apache.logging.log4j.LogManager
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType

object ArgentumCompat {
    private val logger = LogManager.getLogger("Afterimage")

    @JvmStatic
    val isLoaded: Boolean by lazy { FabricLoader.getInstance().isModLoaded("argentum") }

    private class Bridge(
        val worldRenderer: MethodHandle,
        val terrainComplete: MethodHandle,
        val sectionManager: MethodHandle,
        val needsUpdate: MethodHandle,
        val builder: MethodHandle,
        val scheduledJobs: MethodHandle,
    )

    private val bridge: Bridge? by lazy {
        if (!isLoaded) null else runCatching { resolve() }
            .onFailure { logger.warn("Afterimage could not bind to Argentum's terrain renderer; exports will not wait for chunk builds", it) }
            .getOrNull()
    }

    private fun resolve(): Bridge {
        val lookup = MethodHandles.publicLookup()
        val extension = Class.forName("dev.rdh.argentum.impl.ext.WorldRendererExtension")
        val renderer = Class.forName("dev.rdh.argentum.impl.render.terrain.ArgentumWorldRenderer")
        val simpleRenderer = Class.forName("org.embeddedt.embeddium.impl.render.terrain.SimpleWorldRenderer")
        val manager = Class.forName("org.embeddedt.embeddium.impl.render.chunk.RenderSectionManager")
        val builder = Class.forName("org.embeddedt.embeddium.impl.render.chunk.compile.executor.ChunkBuilder")
        return Bridge(
            lookup.findVirtual(extension, "argentum\$getWorldRenderer", MethodType.methodType(renderer)),
            lookup.findVirtual(simpleRenderer, "isTerrainRenderComplete", MethodType.methodType(Boolean::class.javaPrimitiveType)),
            lookup.findVirtual(simpleRenderer, "getRenderSectionManager", MethodType.methodType(manager)),
            lookup.findVirtual(manager, "needsUpdate", MethodType.methodType(Boolean::class.javaPrimitiveType)),
            lookup.findVirtual(manager, "getBuilder", MethodType.methodType(builder)),
            lookup.findVirtual(builder, "getScheduledJobCount", MethodType.methodType(Int::class.javaPrimitiveType)),
        )
    }

    val handlesTerrain: Boolean get() = bridge != null

    fun terrainSettled(worldRenderer: WorldRenderer): Boolean {
        val bridge = bridge ?: return true
        return runCatching {
            val renderer = bridge.worldRenderer.invoke(worldRenderer) ?: return true
            val manager = bridge.sectionManager.invoke(renderer) ?: return true
            bridge.terrainComplete.invoke(renderer) as Boolean && !(bridge.needsUpdate.invoke(manager) as Boolean)
        }.getOrDefault(true)
    }

    fun pendingTerrainDescription(worldRenderer: WorldRenderer): String {
        val bridge = bridge ?: return ""
        return runCatching {
            val renderer = bridge.worldRenderer.invoke(worldRenderer) ?: return ""
            val manager = bridge.sectionManager.invoke(renderer) ?: return ""
            val jobs = bridge.scheduledJobs.invoke(bridge.builder.invoke(manager)) as Int
            val graph = if (bridge.needsUpdate.invoke(manager) as Boolean) " (graph update)" else ""
            "$jobs sections building$graph"
        }.getOrDefault("")
    }
}
