package gg.sona.afterimage.mc26.render

import gg.sona.afterimage.editor.look.LookSettings
import gg.sona.afterimage.gfx.SizedTexture
import gg.sona.afterimage.gfx.TextureFormat
import gg.sona.afterimage.gfx.Viewport
import gg.sona.afterimage.mc26.McGfx26
import gg.sona.afterimage.render.look.PostProcessor
import net.minecraft.client.Minecraft

class LookPreview26(private val minecraft: Minecraft, private val post: PostProcessor) {
    class Request(val look: LookSettings, val focusDistance: Double, val orthographic: Boolean, val tanHalfFov: FloatArray, val seed: Float)

    var request: () -> Request? = { null }
    var viewport: () -> IntArray? = { null }
    private val gfx = McGfx26.gfx
    private val depth = SizedTexture(gfx, TextureFormat.DEPTH24)
    private val scratch = SizedTexture(gfx, TextureFormat.RGBA8)
    private var depthFailed = false

    fun onWorldPassEnd() {
        val request = request() ?: return
        if (!request.look.active || !post.available) return
        val rect = viewport() ?: return
        val width = rect[2]
        val height = rect[3]
        if (width <= 0 || height <= 0) return
        val target = McGfx26.mainTarget(minecraft)
        val depthTexture = if (request.look.depthOfField && !depthFailed) {
            val texture = depth.ensure(width, height)
            if (gfx.copyDepth(target, rect[0], rect[1], width, height, texture)) texture else {
                depthFailed = true
                null
            }
        } else null
        val color = scratch.ensure(width, height)
        gfx.copyColor(target, rect[0], rect[1], width, height, color)
        post.draw(
            PostProcessor.Stage.FULL,
            target,
            Viewport(rect[0], rect[1], width, height),
            color,
            depthTexture,
            PostProcessor.Frame(
                request.look,
                request.focusDistance,
                PostProcessor.NEAR_PLANE,
                PostProcessor.farPlane(minecraft.options.effectiveRenderDistance),
                request.orthographic,
                request.tanHalfFov,
                request.seed,
                PostProcessor.PREVIEW_TAPS,
                PostProcessor.PREVIEW_SPACING,
            ),
        )
    }

    fun release() {
        scratch.close()
        depth.close()
    }
}
