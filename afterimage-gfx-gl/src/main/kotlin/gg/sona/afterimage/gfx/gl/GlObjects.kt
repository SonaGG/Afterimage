package gg.sona.afterimage.gfx.gl

import gg.sona.afterimage.gfx.Filter
import gg.sona.afterimage.gfx.Readback
import gg.sona.afterimage.gfx.Target
import gg.sona.afterimage.gfx.Texture
import gg.sona.afterimage.gfx.Texture3D
import gg.sona.afterimage.gfx.TextureFormat
import org.lwjgl.BufferUtils
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL12
import org.lwjgl.opengl.GL14
import org.lwjgl.opengl.GL15
import org.lwjgl.opengl.GL21
import org.lwjgl.opengl.GL30
import java.nio.ByteBuffer

class GlTexture(
    override val handle: Int,
    override val width: Int,
    override val height: Int,
    val format: TextureFormat,
    private val owned: Boolean,
) : Texture {
    private var closed = false

    override fun setFilter(filter: Filter) {
        withBinding {
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, minFilter(filter))
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, magFilter(filter))
        }
    }

    override fun generateMipmaps() {
        withBinding { GL30.glGenerateMipmap(GL11.GL_TEXTURE_2D) }
    }

    override fun close() {
        if (closed || !owned) return
        closed = true
        GL11.glDeleteTextures(handle)
    }

    private inline fun withBinding(block: () -> Unit) {
        val previous = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D)
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, handle)
        try {
            block()
        } finally {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, previous)
        }
    }

    companion object {
        fun minFilter(filter: Filter): Int = when (filter) {
            Filter.NEAREST -> GL11.GL_NEAREST
            Filter.LINEAR -> GL11.GL_LINEAR
            Filter.LINEAR_MIPMAP -> GL11.GL_LINEAR_MIPMAP_LINEAR
        }

        fun magFilter(filter: Filter): Int = if (filter == Filter.NEAREST) GL11.GL_NEAREST else GL11.GL_LINEAR

        fun internalFormat(format: TextureFormat): Int = when (format) {
            TextureFormat.RGBA8 -> GL11.GL_RGBA8
            TextureFormat.RGBA16 -> GL11.GL_RGBA16
            TextureFormat.R16 -> GL30.GL_R16
            TextureFormat.DEPTH24 -> GL14.GL_DEPTH_COMPONENT24
        }

        fun pixelFormat(format: TextureFormat): Int = when (format) {
            TextureFormat.RGBA8, TextureFormat.RGBA16 -> GL11.GL_RGBA
            TextureFormat.R16 -> GL11.GL_RED
            TextureFormat.DEPTH24 -> GL11.GL_DEPTH_COMPONENT
        }

        fun pixelType(format: TextureFormat): Int = when (format) {
            TextureFormat.RGBA8 -> GL11.GL_UNSIGNED_BYTE
            TextureFormat.RGBA16, TextureFormat.R16 -> GL11.GL_UNSIGNED_SHORT
            TextureFormat.DEPTH24 -> GL11.GL_UNSIGNED_INT
        }

        fun create(width: Int, height: Int, format: TextureFormat, filter: Filter, pixels: ByteBuffer?): GlTexture {
            val id = GL11.glGenTextures()
            val previous = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D)
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, id)
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, minFilter(filter))
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, magFilter(filter))
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE)
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE)
            if (format == TextureFormat.DEPTH24) {
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL14.GL_TEXTURE_COMPARE_MODE, GL11.GL_NONE)
            }
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, if (pixels != null && format == TextureFormat.RGBA8) 4 else 1)
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, internalFormat(format), width, height, 0, pixelFormat(format), pixelType(format), pixels)
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, previous)
            return GlTexture(id, width, height, format, owned = true)
        }
    }
}

class GlTexture3D(override val handle: Int, override val size: Int) : Texture3D {
    override fun close() {
        GL11.glDeleteTextures(handle)
    }

    companion object {
        fun create(size: Int, rgb: FloatArray): GlTexture3D {
            val id = GL11.glGenTextures()
            val previous = GL11.glGetInteger(GL12.GL_TEXTURE_BINDING_3D)
            GL11.glBindTexture(GL12.GL_TEXTURE_3D, id)
            GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR)
            GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR)
            GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE)
            GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE)
            GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL12.GL_TEXTURE_WRAP_R, GL12.GL_CLAMP_TO_EDGE)
            val buffer = BufferUtils.createFloatBuffer(rgb.size)
            buffer.put(rgb).flip()
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1)
            GL12.glTexImage3D(GL12.GL_TEXTURE_3D, 0, GL30.GL_RGB16F, size, size, size, 0, GL11.GL_RGB, GL11.GL_FLOAT, buffer)
            GL11.glBindTexture(GL12.GL_TEXTURE_3D, previous)
            return GlTexture3D(id, size)
        }
    }
}

class GlTarget(
    val framebuffer: Int,
    override val color: GlTexture?,
    override val width: Int,
    override val height: Int,
    private val owned: Boolean,
) : Target {
    private var closed = false

    val format: TextureFormat get() = color?.format ?: TextureFormat.RGBA8

    fun bind(binding: Int = GL30.GL_FRAMEBUFFER) = GL30.glBindFramebuffer(binding, framebuffer)

    override fun close() {
        if (closed || !owned) return
        closed = true
        GL30.glDeleteFramebuffers(framebuffer)
        color?.close()
    }

    companion object {
        val DEFAULT = GlTarget(0, null, 0, 0, owned = false)

        fun of(target: Target?): GlTarget = target as? GlTarget ?: DEFAULT

        fun create(width: Int, height: Int, format: TextureFormat, filter: Filter): GlTarget {
            val texture = GlTexture.create(width, height, format, filter, null)
            val framebuffer = GL30.glGenFramebuffers()
            val previous = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING)
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer)
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, texture.handle, 0)
            val status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER)
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, previous)
            if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
                GL30.glDeleteFramebuffers(framebuffer)
                texture.close()
                throw IllegalStateException("framebuffer is incomplete (0x${Integer.toHexString(status)})")
            }
            return GlTarget(framebuffer, texture, width, height, owned = true)
        }
    }
}

class GlReadback(private val target: GlTarget, override val slots: Int) : Readback {
    private val format = target.format
    private val bytesPerPixel = if (format == TextureFormat.R16) 2 else 4
    private val bytes = target.width.toLong() * target.height * bytesPerPixel
    private val buffers = IntArray(slots) { GL15.glGenBuffers() }
    private val staging = BufferUtils.createByteBuffer(bytes.toInt())

    init {
        for (buffer in buffers) {
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, buffer)
            GL15.glBufferData(GL21.GL_PIXEL_PACK_BUFFER, bytes, GL15.GL_STREAM_READ)
        }
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0)
    }

    override fun issue(slot: Int) {
        val previous = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING)
        target.bind(GL30.GL_READ_FRAMEBUFFER)
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, buffers[slot])
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1)
        if (format == TextureFormat.R16) {
            GL11.glReadPixels(0, 0, target.width, target.height, GL11.GL_RED, GL11.GL_UNSIGNED_SHORT, 0L)
        } else {
            GL11.glReadPixels(0, 0, target.width, target.height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, 0L)
        }
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0)
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, previous)
    }

    private fun fetch(slot: Int) {
        staging.clear()
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, buffers[slot])
        GL15.glGetBufferSubData(GL21.GL_PIXEL_PACK_BUFFER, 0L, staging)
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0)
    }

    override fun collectRgba(slot: Int, into: ByteArray) {
        fetch(slot)
        val width = target.width
        val height = target.height
        val rowBytes = width * 4
        for (row in 0 until height) {
            staging.position((height - 1 - row) * rowBytes)
            staging.get(into, row * rowBytes, rowBytes)
        }
    }

    override fun collectR16(slot: Int, into: FloatArray) {
        fetch(slot)
        val width = target.width
        val height = target.height
        val shorts = staging.asShortBuffer()
        for (row in 0 until height) {
            val source = (height - 1 - row) * width
            val destination = row * width
            for (x in 0 until width) into[destination + x] = (shorts.get(source + x).toInt() and 0xFFFF) / 65535f
        }
    }

    override fun close() {
        for (buffer in buffers) GL15.glDeleteBuffers(buffer)
    }
}
