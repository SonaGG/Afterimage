package gg.sona.afterimage.mc

import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL12
import org.lwjgl.opengl.GL15
import org.lwjgl.opengl.GL21
import org.lwjgl.opengl.GL30
import java.nio.ByteBuffer

class DepthMapBuffer(val width: Int, val height: Int) {
    val texture = GL11.glGenTextures()
    private val framebuffer = GL30.glGenFramebuffers()
    private val readbacks = IntArray(AccumulationBuffer.READBACK_SLOTS) { GL15.glGenBuffers() }
    private val bytes = width.toLong() * height * 2L

    init {
        val previous = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D)
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE)
        GL11.glTexImage2D(
            GL11.GL_TEXTURE_2D, 0, GL30.GL_R16, width, height, 0,
            GL11.GL_RED, GL11.GL_UNSIGNED_SHORT, null as ByteBuffer?
        )
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, previous)
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
            throw IllegalStateException("depth map framebuffer is incomplete (0x${Integer.toHexString(status)})")
        }
    }

    fun bind() = GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer)

    fun issueReadback(slot: Int) {
        bind()
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, readbacks[slot])
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1)
        GL11.glReadPixels(0, 0, width, height, GL11.GL_RED, GL11.GL_UNSIGNED_SHORT, 0L)
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0)
    }

    fun collectReadback(slot: Int, staging: ByteBuffer, into: FloatArray) {
        staging.clear()
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, readbacks[slot])
        GL15.glGetBufferSubData(GL21.GL_PIXEL_PACK_BUFFER, 0L, staging)
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0)
        val shorts = staging.asShortBuffer()
        for (row in 0 until height) {
            val source = (height - 1 - row) * width
            val target = row * width
            for (x in 0 until width) into[target + x] = (shorts.get(source + x).toInt() and 0xFFFF) / 65535f
        }
    }

    fun destroy() {
        for (buffer in readbacks) GL15.glDeleteBuffers(buffer)
        GL30.glDeleteFramebuffers(framebuffer)
        GL11.glDeleteTextures(texture)
    }
}
