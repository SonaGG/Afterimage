package gg.sona.afterimage.mc

import net.minecraft.client.render.platform.GlStateManager
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL12
import org.lwjgl.opengl.GL15
import org.lwjgl.opengl.GL21
import org.lwjgl.opengl.GL30
import java.nio.ByteBuffer

class AccumulationBuffer(val width: Int, val height: Int, private val internalFormat: Int = GL11.GL_RGBA16) {
    val texture = GL11.glGenTextures()
    private val framebuffer = GL30.glGenFramebuffers()
    private val readbacks = IntArray(READBACK_SLOTS) { GL15.glGenBuffers() }
    private val bytes = width.toLong() * height * 4L

    init {
        GlStateManager.bindTexture(texture)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE)
        GL11.glTexImage2D(
            GL11.GL_TEXTURE_2D, 0, internalFormat, width, height, 0,
            GL11.GL_RGBA, GL11.GL_UNSIGNED_SHORT, null as ByteBuffer?
        )
        GlStateManager.bindTexture(0)
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer)
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, texture, 0)
        val status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER)
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0)
        for (buffer in readbacks) {
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, buffer)
            GL15.glBufferData(GL21.GL_PIXEL_PACK_BUFFER, bytes, GL15.GL_STREAM_READ)
        }
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0)
        if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
            destroy()
            throw IllegalStateException("accumulation framebuffer is incomplete (0x${Integer.toHexString(status)})")
        }
    }

    fun bind() = GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer)

    fun clear() {
        bind()
        GL11.glPushAttrib(GL11.GL_COLOR_BUFFER_BIT or GL11.GL_SCISSOR_BIT)
        GL11.glDisable(GL11.GL_SCISSOR_TEST)
        GL11.glColorMask(true, true, true, true)
        GL11.glClearColor(0f, 0f, 0f, 0f)
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT)
        GL11.glPopAttrib()
    }

    fun issueReadback(slot: Int) {
        bind()
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, readbacks[slot])
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1)
        GL11.glReadPixels(0, 0, width, height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, 0L)
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0)
    }

    fun collectReadback(slot: Int, staging: ByteBuffer, into: ByteArray) {
        staging.clear()
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, readbacks[slot])
        GL15.glGetBufferSubData(GL21.GL_PIXEL_PACK_BUFFER, 0L, staging)
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0)
        val rowBytes = width * 4
        for (row in 0 until height) {
            staging.position((height - 1 - row) * rowBytes)
            staging.get(into, row * rowBytes, rowBytes)
        }
    }

    fun destroy() {
        GL15.glDeleteBuffers(readbacks[0])
        GL15.glDeleteBuffers(readbacks[1])
        GL30.glDeleteFramebuffers(framebuffer)
        GL11.glDeleteTextures(texture)
    }

    companion object {
        const val READBACK_SLOTS = 2
    }
}
