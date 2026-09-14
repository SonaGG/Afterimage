package gg.sona.recast.mc

import gg.sona.recast.editor.look.LookSettings
import net.minecraft.client.Minecraft
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL12
import java.nio.ByteBuffer

class LookPreview(private val minecraft: Minecraft, private val post: PostProcessor) {

    class Request(val look: LookSettings, val focusDistance: Double, val orthographic: Boolean, val seed: Float)

    var request: () -> Request? = { null }
    var viewport: () -> IntArray? = { null }

    private val depth = DepthTexture()
    private var scratch = 0
    private var scratchWidth = 0
    private var scratchHeight = 0
    private var depthCaptured = false

    fun onWorldPassEnd() {
        depthCaptured = false
        val request = request() ?: return
        if (!request.look.depthOfField) return
        val rect = viewport() ?: return
        if (rect[2] <= 0 || rect[3] <= 0) return
        depthCaptured = depth.copyFromReadFramebuffer(rect[0], rect[1], rect[2], rect[3])
    }

    fun onHudRendered() {
        val request = request() ?: return
        if (!request.look.active || !post.available) return
        val rect = viewport() ?: return
        val width = rect[2]
        val height = rect[3]
        if (width <= 0 || height <= 0) return
        ensureScratch(width, height)
        val previous = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D)
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, scratch)
        GL11.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, rect[0], rect[1], width, height)
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, previous)
        GL11.glPushAttrib(GL11.GL_VIEWPORT_BIT)
        try {
            GL11.glViewport(rect[0], rect[1], width, height)
            post.drawLook(
                scratch,
                if (depthCaptured && request.look.depthOfField) depth.id else 0,
                width,
                height,
                PostProcessor.Frame(
                    request.look,
                    request.focusDistance,
                    PostProcessor.NEAR_PLANE,
                    PostProcessor.farPlane(minecraft.options.viewDistance),
                    request.orthographic,
                    request.seed,
                    PostProcessor.PREVIEW_TAPS,
                ),
            )
        } finally {
            GL11.glPopAttrib()
        }
    }

    private fun ensureScratch(width: Int, height: Int) {
        if (scratch != 0 && scratchWidth == width && scratchHeight == height) return
        if (scratch != 0) GL11.glDeleteTextures(scratch)
        scratch = GL11.glGenTextures()
        scratchWidth = width
        scratchHeight = height
        val previous = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D)
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, scratch)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE)
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, width, height, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, null as ByteBuffer?)
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, previous)
    }

    fun release() {
        if (scratch != 0) GL11.glDeleteTextures(scratch)
        scratch = 0
        scratchWidth = 0
        scratchHeight = 0
        depth.destroy()
        depthCaptured = false
    }
}
