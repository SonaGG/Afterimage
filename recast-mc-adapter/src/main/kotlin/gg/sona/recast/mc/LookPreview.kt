package gg.sona.recast.mc

import gg.sona.recast.editor.look.LookSettings
import net.minecraft.client.Minecraft
import org.lwjgl.opengl.GL11

class LookPreview(private val minecraft: Minecraft, private val post: PostProcessor) {

    class Request(val look: LookSettings, val focusDistance: Double, val orthographic: Boolean, val tanHalfFov: FloatArray, val seed: Float)

    var request: () -> Request? = { null }
    var viewport: () -> IntArray? = { null }

    private val depth = DepthTexture()
    private val scratch = ScratchTexture()

    fun onWorldPassEnd() {
        val request = request() ?: return
        if (!request.look.active || !post.available) return
        val rect = viewport() ?: return
        val width = rect[2]
        val height = rect[3]
        if (width <= 0 || height <= 0) return
        val depthTexture = if (request.look.depthOfField && depth.copyFromReadFramebuffer(rect[0], rect[1], width, height)) depth.id else 0
        scratch.copyFromReadFramebuffer(rect[0], rect[1], width, height)
        GL11.glPushAttrib(GL11.GL_VIEWPORT_BIT)
        try {
            GL11.glViewport(rect[0], rect[1], width, height)
            post.draw(
                PostProcessor.Stage.FULL,
                scratch.id,
                depthTexture,
                width,
                height,
                PostProcessor.Frame(
                    request.look,
                    request.focusDistance,
                    PostProcessor.NEAR_PLANE,
                    PostProcessor.farPlane(minecraft.options.viewDistance),
                    request.orthographic,
                    request.tanHalfFov,
                    request.seed,
                    PostProcessor.PREVIEW_TAPS,
                    PostProcessor.PREVIEW_SPACING,
                ),
            )
        } finally {
            GL11.glPopAttrib()
        }
    }

    fun release() {
        scratch.destroy()
        depth.destroy()
    }
}
