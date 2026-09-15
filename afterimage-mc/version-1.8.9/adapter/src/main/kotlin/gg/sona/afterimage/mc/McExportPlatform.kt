package gg.sona.afterimage.mc

import gg.sona.afterimage.gfx.Filter
import gg.sona.afterimage.gfx.Gfx
import gg.sona.afterimage.gfx.Target
import gg.sona.afterimage.mc.McGfx.asTarget
import gg.sona.afterimage.mc.McGfx.makeOpaque
import gg.sona.afterimage.mc.McGfx.setFilter
import gg.sona.afterimage.mc.common.ExportPlatform
import gg.sona.afterimage.mc.common.ExportSurface
import net.minecraft.client.Minecraft
import net.minecraft.client.entity.living.player.ClientPlayerEntity
import net.minecraft.client.render.pipeline.RenderTarget

class McExportPlatform(private val minecraft: Minecraft) : ExportPlatform {
    class Surface(val target: RenderTarget) : ExportSurface {
        override val width: Int get() = target.width
        override val height: Int get() = target.height
        override val colorHandle: Int get() = target.colorTextureId
        override fun asTarget(): Target = target.asTarget()
        override fun makeOpaque() = target.makeOpaque()
        override fun setFilter(filter: Filter) = target.setFilter(filter)
        override fun close() = target.destroyBuffers()
    }

    private val chunks = ChunkReadiness(minecraft)
    private var savedWidth = 0
    private var savedHeight = 0

    override val gfx: Gfx get() = McGfx.gfx
    override val windowWidth: Int get() = minecraft.width
    override val windowHeight: Int get() = minecraft.height

    override fun createSurface(width: Int, height: Int, transparent: Boolean): ExportSurface =
        Surface(RenderTarget(width, height, true).also { it.setClearColor(0f, 0f, 0f, if (transparent) 0f else 1f) })

    override fun beginRender(surface: ExportSurface) {
        savedWidth = minecraft.width
        savedHeight = minecraft.height
        minecraft.width = surface.width
        minecraft.height = surface.height
        (surface as Surface).target.bindWrite(true)
    }

    override fun endRender() {
        minecraft.width = savedWidth
        minecraft.height = savedHeight
        val window = minecraft.renderTarget
        window.bindWrite(true)
        gfx.clear(window.asTarget(), 0.05f, 0.05f, 0.06f, 1f, depth = true)
    }

    override fun setHideGui(hide: Boolean): Boolean {
        val previous = minecraft.options.hideGui
        minecraft.options.hideGui = hide
        return previous
    }

    override fun viewDistance(): Int = minecraft.options.viewDistance

    override fun calibrateChunks() = chunks.calibrate()

    override fun chunksSettled(): Boolean = chunks.settled()

    override fun pendingChunkDescription(): String = chunks.pendingDescription()

    override fun missingSkins(): List<Int> {
        val world = minecraft.world ?: return emptyList()
        val camera = minecraft.camera
        val missing = ArrayList<Int>()
        for (entity in world.entities) {
            if (entity !is ClientPlayerEntity || entity === minecraft.player || entity.hasSkinTexture()) continue
            if (camera != null) {
                val dx = entity.x - camera.x
                val dy = entity.y - camera.y
                val dz = entity.z - camera.z
                if (dx * dx + dy * dy + dz * dz > SKIN_WAIT_RANGE * SKIN_WAIT_RANGE) continue
            }
            missing += entity.networkId
        }
        return missing
    }

    private companion object {
        const val SKIN_WAIT_RANGE = 96.0
    }
}
