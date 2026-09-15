package gg.sona.afterimage.mc26

import gg.sona.afterimage.gfx.ColorMask
import gg.sona.afterimage.gfx.Target
import gg.sona.afterimage.mc26.gfx.RenderpearlGfx
import gg.sona.afterimage.mc26.gfx.RpTarget
import net.minecraft.client.Minecraft
import com.mojang.blaze3d.pipeline.RenderTarget
import org.slf4j.LoggerFactory

object McGfx26 {
    private val logger = LoggerFactory.getLogger("Afterimage")
    private var instance: RenderpearlGfx? = null

    val gfx: RenderpearlGfx
        get() = instance ?: RenderpearlGfx { logger.warn("(Afterimage) {}", it) }.also { instance = it }

    fun mainTarget(minecraft: Minecraft): RpTarget = gfx.wrap(minecraft.gameRenderer.mainRenderTarget())

    fun RenderTarget.asTarget(): RpTarget = gfx.wrap(this)

    fun RenderTarget.makeOpaque() = gfx.clear(asTarget(), 0f, 0f, 0f, 1f, ColorMask.ALPHA)

    fun shutdown() {
        instance?.close()
        instance = null
    }
}
