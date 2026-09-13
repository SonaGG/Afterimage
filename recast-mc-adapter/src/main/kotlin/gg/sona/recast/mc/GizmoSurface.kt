package gg.sona.recast.mc

import org.apache.logging.log4j.LogManager
import org.lwjgl.opengl.*
import java.nio.ByteBuffer

class GizmoSurface {
    private var disabled = false
    private var framebuffer = 0
    private var colorBuffer = 0
    private var depthBuffer = 0
    private var resolveFramebuffer = 0
    private var resolveTexture = 0
    private var width = 0
    private var height = 0
    private var depthFormat = 0

    fun begin(sourceFramebuffer: Int, x: Int, y: Int, w: Int, h: Int): Boolean {
        if (disabled || w <= 0 || h <= 0) return false
        val capabilities = GL.getCapabilities()
        if (!capabilities.OpenGL30) return disable("OpenGL 3.0 framebuffer blits are unavailable")
        val format = sourceDepthFormat(sourceFramebuffer)
        if (format == 0) return disable("could not determine the depth format of framebuffer $sourceFramebuffer")
        if (!ensure(w, h, format)) return false
        drainErrors()
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, sourceFramebuffer)
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, framebuffer)
        GL30.glBlitFramebuffer(x, y, x + w, y + h, 0, 0, w, h, GL11.GL_DEPTH_BUFFER_BIT, GL11.GL_NEAREST)
        val error = GL11.glGetError()
        if (error != GL11.GL_NO_ERROR) {
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, sourceFramebuffer)
            return disable(
                "depth blit failed with GL error 0x${Integer.toHexString(error)} (format 0x${
                    Integer.toHexString(
                        format
                    )
                })"
            )
        }
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer)
        GL11.glViewport(0, 0, w, h)
        GL11.glEnable(GL13.GL_MULTISAMPLE)
        GL11.glColorMask(true, true, true, true)
        GL11.glClearColor(0f, 0f, 0f, 0f)
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT)
        GL14.glBlendFuncSeparate(
            GL11.GL_SRC_ALPHA,
            GL11.GL_ONE_MINUS_SRC_ALPHA,
            GL11.GL_ONE,
            GL11.GL_ONE_MINUS_SRC_ALPHA
        )
        return true
    }

    fun end(sourceFramebuffer: Int, x: Int, y: Int, w: Int, h: Int) {
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, framebuffer)
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, resolveFramebuffer)
        GL30.glBlitFramebuffer(0, 0, w, h, 0, 0, w, h, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST)
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, sourceFramebuffer)
        GL11.glViewport(x, y, w, h)

        GL11.glDisable(GL11.GL_DEPTH_TEST)
        GL11.glDepthMask(false)
        GL11.glDisable(GL13.GL_MULTISAMPLE)
        GL11.glEnable(GL11.GL_BLEND)
        GL11.glBlendFunc(GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA)
        GL13.glActiveTexture(GL13.GL_TEXTURE0)
        GL11.glEnable(GL11.GL_TEXTURE_2D)
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, resolveTexture)
        GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, GL11.GL_TEXTURE_ENV_MODE, GL11.GL_MODULATE)
        GL11.glColor4f(1f, 1f, 1f, 1f)
        GL11.glMatrixMode(GL11.GL_PROJECTION)
        GL11.glPushMatrix()
        GL11.glLoadIdentity()
        GL11.glMatrixMode(GL11.GL_MODELVIEW)
        GL11.glPushMatrix()
        GL11.glLoadIdentity()
        GL11.glBegin(GL11.GL_QUADS)
        GL11.glTexCoord2f(0f, 0f)
        GL11.glVertex2f(-1f, -1f)
        GL11.glTexCoord2f(1f, 0f)
        GL11.glVertex2f(1f, -1f)
        GL11.glTexCoord2f(1f, 1f)
        GL11.glVertex2f(1f, 1f)
        GL11.glTexCoord2f(0f, 1f)
        GL11.glVertex2f(-1f, 1f)
        GL11.glEnd()
        GL11.glPopMatrix()
        GL11.glMatrixMode(GL11.GL_PROJECTION)
        GL11.glPopMatrix()
        GL11.glMatrixMode(GL11.GL_MODELVIEW)
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0)
    }

    fun destroy() {
        if (framebuffer != 0) GL30.glDeleteFramebuffers(framebuffer)
        if (resolveFramebuffer != 0) GL30.glDeleteFramebuffers(resolveFramebuffer)
        if (colorBuffer != 0) GL30.glDeleteRenderbuffers(colorBuffer)
        if (depthBuffer != 0) GL30.glDeleteRenderbuffers(depthBuffer)
        if (resolveTexture != 0) GL11.glDeleteTextures(resolveTexture)
        framebuffer = 0
        resolveFramebuffer = 0
        colorBuffer = 0
        depthBuffer = 0
        resolveTexture = 0
        width = 0
        height = 0
    }

    private fun ensure(w: Int, h: Int, format: Int): Boolean {
        if (framebuffer != 0 && width == w && height == h && depthFormat == format) return true
        destroy()
        val wanted = minOf(MAX_SAMPLES, GL11.glGetInteger(GL30.GL_MAX_SAMPLES))
        if (wanted < 2) return disable("multisampling is unsupported")
        drainErrors()
        colorBuffer = GL30.glGenRenderbuffers()
        GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, colorBuffer)
        GL30.glRenderbufferStorageMultisample(GL30.GL_RENDERBUFFER, wanted, GL11.GL_RGBA8, w, h)
        depthBuffer = GL30.glGenRenderbuffers()
        GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, depthBuffer)
        GL30.glRenderbufferStorageMultisample(GL30.GL_RENDERBUFFER, wanted, format, w, h)
        GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, 0)
        framebuffer = GL30.glGenFramebuffers()
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer)
        GL30.glFramebufferRenderbuffer(
            GL30.GL_FRAMEBUFFER,
            GL30.GL_COLOR_ATTACHMENT0,
            GL30.GL_RENDERBUFFER,
            colorBuffer
        )
        val depthAttachment = if (hasStencil(format)) GL30.GL_DEPTH_STENCIL_ATTACHMENT else GL30.GL_DEPTH_ATTACHMENT
        GL30.glFramebufferRenderbuffer(GL30.GL_FRAMEBUFFER, depthAttachment, GL30.GL_RENDERBUFFER, depthBuffer)
        val status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER)

        resolveTexture = GL11.glGenTextures()
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, resolveTexture)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE)
        GL11.glTexImage2D(
            GL11.GL_TEXTURE_2D,
            0,
            GL11.GL_RGBA8,
            w,
            h,
            0,
            GL11.GL_RGBA,
            GL11.GL_UNSIGNED_BYTE,
            null as ByteBuffer?
        )
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0)
        resolveFramebuffer = GL30.glGenFramebuffers()
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, resolveFramebuffer)
        GL30.glFramebufferTexture2D(
            GL30.GL_FRAMEBUFFER,
            GL30.GL_COLOR_ATTACHMENT0,
            GL11.GL_TEXTURE_2D,
            resolveTexture,
            0
        )
        val resolveStatus = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER)
        val error = GL11.glGetError()
        if (status != GL30.GL_FRAMEBUFFER_COMPLETE || resolveStatus != GL30.GL_FRAMEBUFFER_COMPLETE || error != GL11.GL_NO_ERROR) {
            destroy()
            return disable(
                "framebuffer incomplete (status 0x${Integer.toHexString(status)}, resolve 0x${
                    Integer.toHexString(
                        resolveStatus
                    )
                }, error 0x${Integer.toHexString(error)})"
            )
        }
        width = w
        height = h
        depthFormat = format

        return true
    }

    private fun sourceDepthFormat(framebufferId: Int): Int {
        if (framebufferId == 0) {
            val depthBits = GL11.glGetInteger(GL11.GL_DEPTH_BITS)
            val stencilBits = GL11.glGetInteger(GL11.GL_STENCIL_BITS)
            return when {
                depthBits == 0 -> 0
                stencilBits > 0 -> GL30.GL_DEPTH24_STENCIL8
                depthBits >= 32 -> GL14.GL_DEPTH_COMPONENT32
                depthBits >= 24 -> GL14.GL_DEPTH_COMPONENT24
                else -> GL14.GL_DEPTH_COMPONENT16
            }
        }
        val type = GL30.glGetFramebufferAttachmentParameteri(
            GL30.GL_DRAW_FRAMEBUFFER,
            GL30.GL_DEPTH_ATTACHMENT,
            GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE
        )
        if (type != GL30.GL_RENDERBUFFER && type != GL11.GL_TEXTURE) return 0
        val name = GL30.glGetFramebufferAttachmentParameteri(
            GL30.GL_DRAW_FRAMEBUFFER,
            GL30.GL_DEPTH_ATTACHMENT,
            GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME
        )
        return when (type) {
            GL30.GL_RENDERBUFFER -> {
                GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, name)
                val format =
                    GL30.glGetRenderbufferParameteri(GL30.GL_RENDERBUFFER, GL30.GL_RENDERBUFFER_INTERNAL_FORMAT)
                GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, 0)
                format
            }

            GL11.GL_TEXTURE -> {
                val previous = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D)
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, name)
                val format = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_INTERNAL_FORMAT)
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, previous)
                format
            }

            else -> 0
        }
    }

    private fun hasStencil(format: Int): Boolean =
        format == GL30.GL_DEPTH24_STENCIL8 || format == GL30.GL_DEPTH32F_STENCIL8 || format == GL30.GL_DEPTH_STENCIL

    private fun drainErrors() {
        var guard = 0
        while (GL11.glGetError() != GL11.GL_NO_ERROR && guard++ < 16) Unit
    }

    private fun disable(reason: String): Boolean {
        if (!disabled) logger.warn("(Recast) gizmo anti-aliasing disabled: {}", reason)
        disabled = true
        destroy()
        return false
    }

    private companion object {
        val logger = LogManager.getLogger("Recast")
        const val MAX_SAMPLES = 8
    }
}
