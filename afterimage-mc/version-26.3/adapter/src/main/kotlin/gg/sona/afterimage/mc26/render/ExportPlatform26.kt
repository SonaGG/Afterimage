package gg.sona.afterimage.mc26.render

import gg.sona.afterimage.gfx.Filter
import gg.sona.afterimage.gfx.Gfx
import gg.sona.afterimage.gfx.Target
import gg.sona.afterimage.mc.common.ExportPlatform
import gg.sona.afterimage.mc.common.ExportSurface
import gg.sona.afterimage.mc26.McGfx26
import gg.sona.afterimage.mc26.mixin.HudAccessor
import gg.sona.afterimage.mc26.replay.ChunkReadiness26
import net.minecraft.client.Minecraft
import net.minecraft.client.player.AbstractClientPlayer
import net.minecraft.client.resources.DefaultPlayerSkin

class ExportPlatform26(private val minecraft: Minecraft) : ExportPlatform {
    private inner class Surface(override val width: Int, override val height: Int, private val transparent: Boolean) : ExportSurface {
        private var filter = Filter.LINEAR

        override val colorHandle: Int get() = asTarget().color?.handle ?: 0

        override fun asTarget(): Target = McGfx26.mainTarget(minecraft).also { it.color?.setFilter(filter) }

        override fun makeOpaque() {
            McGfx26.gfx.clear(asTarget(), 0f, 0f, 0f, 1f, gg.sona.afterimage.gfx.ColorMask.ALPHA)
        }

        override fun setFilter(filter: Filter) {
            this.filter = filter
        }

        override fun close() {
            if (active === this) active = null
        }
    }

    private val chunks = ChunkReadiness26(minecraft)

    @Volatile
    private var active: Surface? = null

    val sizeOverride: IntArray? get() = active?.let { intArrayOf(it.width, it.height) }

    override val gfx: Gfx get() = McGfx26.gfx
    override val windowWidth: Int get() = active?.width ?: minecraft.window.width
    override val windowHeight: Int get() = active?.height ?: minecraft.window.height

    override fun createSurface(width: Int, height: Int, transparent: Boolean): ExportSurface = Surface(width, height, transparent).also { active = it }

    override fun beginRender(surface: ExportSurface) = Unit

    override fun endRender() = Unit

    override fun setHideGui(hide: Boolean): Boolean {
        val hud = minecraft.gui.hud
        val previous = hud.isHidden
        if (previous != hide) (hud as HudAccessor).afterimage_setHidden(hide)
        return previous
    }

    override fun viewDistance(): Int = minecraft.options.effectiveRenderDistance

    override fun calibrateChunks() = chunks.calibrate()

    override fun chunksSettled(): Boolean = chunks.settled()

    override fun pendingChunkDescription(): String = chunks.pendingDescription()

    override fun missingSkins(): List<Int> {
        val level = minecraft.level ?: return emptyList()
        val camera = minecraft.cameraEntity
        val missing = ArrayList<Int>()
        for (entity in level.entitiesForRendering()) {
            if (entity !is AbstractClientPlayer || entity === minecraft.player) continue
            if (entity.skin != DefaultPlayerSkin.get(entity.uuid)) continue
            if (camera != null) {
                val dx = entity.x - camera.x
                val dy = entity.y - camera.y
                val dz = entity.z - camera.z
                if (dx * dx + dy * dy + dz * dz > SKIN_WAIT_RANGE * SKIN_WAIT_RANGE) continue
            }
            missing += entity.id
        }
        return missing
    }

    private companion object {
        const val SKIN_WAIT_RANGE = 96.0
    }
}
