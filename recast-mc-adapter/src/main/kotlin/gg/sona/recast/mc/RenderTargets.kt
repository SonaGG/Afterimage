package gg.sona.recast.mc

import net.minecraft.client.render.pipeline.RenderTarget
import net.minecraft.client.render.platform.GlStateManager
import org.lwjgl.opengl.GL11

object RenderTargets {
    fun makeOpaque(target: RenderTarget) {
        target.bindWrite(false)
        GlStateManager.colorMask(false, false, false, true)
        GlStateManager.clearColor(0f, 0f, 0f, 1f)
        GlStateManager.clear(GL11.GL_COLOR_BUFFER_BIT)
        GlStateManager.colorMask(true, true, true, true)
    }
}
