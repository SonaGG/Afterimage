package gg.sona.afterimage.mc189

import gg.sona.afterimage.gfx.ColorMask
import gg.sona.afterimage.gfx.Filter
import gg.sona.afterimage.gfx.gl.GlGfx
import gg.sona.afterimage.gfx.gl.GlTarget
import net.minecraft.client.render.pipeline.RenderTarget
import org.apache.logging.log4j.LogManager

object McGfx {
    private val logger = LogManager.getLogger("Afterimage")
    private var instance: GlGfx? = null

    val gfx: GlGfx
        get() = instance ?: GlGfx(GLSL_VERSION, legacy = true) { logger.warn("(Afterimage) {}", it) }.also { instance = it }

    fun RenderTarget.asTarget(): GlTarget = gfx.wrap(frameBufferId, colorTextureId, width, height)

    fun RenderTarget.makeOpaque() = gfx.clear(asTarget(), 0f, 0f, 0f, 1f, ColorMask.ALPHA)

    fun RenderTarget.setFilter(filter: Filter) {
        asTarget().color?.setFilter(filter)
    }

    fun shutdown() {
        instance?.close()
        instance = null
    }

    private const val GLSL_VERSION = 120
}
