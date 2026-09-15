package gg.sona.afterimage.gfx.gl

import gg.sona.afterimage.gfx.GizmoGeometry
import gg.sona.afterimage.gfx.GizmoPass
import gg.sona.afterimage.gfx.Target
import gg.sona.afterimage.gfx.Viewport
import org.lwjgl.BufferUtils
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL13
import org.lwjgl.opengl.GL14
import org.lwjgl.opengl.GL15
import org.lwjgl.opengl.GL20
import org.lwjgl.opengl.GL30
import java.nio.ByteBuffer

class GlGizmoPass(
    private val state: GlState,
    private val quad: GlQuad,
    private val blit: GlProgram,
    private val legacy: Boolean,
    glslVersion: Int,
    private val warn: (String) -> Unit,
) : GizmoPass {
    private val hasVao = GL.getCapabilities().OpenGL30
    private val program = GlProgram.link(
        Glsl.vertex(VERTEX_SOURCE, glslVersion),
        Glsl.fragment(FRAGMENT_SOURCE, glslVersion),
        listOf("mvp"),
        listOf("Position", "Color"),
    )
    private val buffer = GL15.glGenBuffers()
    private val vao = if (hasVao) GL30.glGenVertexArrays() else 0
    private val matrix = BufferUtils.createFloatBuffer(16)
    private var vertices: ByteBuffer = BufferUtils.createByteBuffer(STRIDE * 1024)
    private val surface = MsaaSurface()

    override fun draw(
        target: Target?,
        viewport: Viewport,
        viewProjection: FloatArray,
        originX: Double,
        originY: Double,
        originZ: Double,
        geometry: GizmoGeometry,
    ) {
        val framebuffer = GlTarget.of(target).framebuffer
        state.withState {
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer)
            state.prepareShaderDraw()
            GL11.glEnable(GL11.GL_BLEND)
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA)
            val offscreen = surface.begin(framebuffer, viewport)
            if (!offscreen && legacy) {
                GL11.glEnable(GL11.GL_LINE_SMOOTH)
                GL11.glHint(GL11.GL_LINE_SMOOTH_HINT, GL11.GL_NICEST)
            }
            GL20.glUseProgram(program.id)
            matrix.clear()
            matrix.put(viewProjection, 0, 16).flip()
            GL20.glUniformMatrix4fv(program["mvp"], false, matrix)
            if (hasVao) GL30.glBindVertexArray(vao)
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, buffer)
            GL20.glEnableVertexAttribArray(0)
            GL20.glEnableVertexAttribArray(1)

            GL11.glEnable(GL11.GL_DEPTH_TEST)
            GL11.glDepthMask(false)
            GL11.glDepthFunc(GL11.GL_GREATER)
            triangles(geometry, WORLD, OCCLUDED_ALPHA, originX, originY, originZ)
            lines(geometry, WORLD, OCCLUDED_ALPHA, originX, originY, originZ)

            GL11.glDepthFunc(GL11.GL_LEQUAL)
            GL11.glDepthMask(true)
            triangles(geometry, WORLD, 1f, originX, originY, originZ)
            GL11.glDepthMask(false)
            lines(geometry, WORLD, 1f, originX, originY, originZ)

            GL11.glDepthMask(true)
            GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT)
            triangles(geometry, OVERLAY, 1f, originX, originY, originZ)
            GL11.glDepthMask(false)
            lines(geometry, OVERLAY, 1f, originX, originY, originZ)

            GL20.glDisableVertexAttribArray(0)
            GL20.glDisableVertexAttribArray(1)
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0)
            if (hasVao) GL30.glBindVertexArray(0)
            if (offscreen) surface.end(framebuffer, viewport)
        }
    }

    private fun triangles(geometry: GizmoGeometry, layer: Int, alphaScale: Float, ox: Double, oy: Double, oz: Double) {
        val count = geometry.triCount
        if (count == 0) return
        val positions = geometry.triPositions
        val colors = geometry.triColors
        val layers = geometry.triLayers
        val data = reserve(count * 3)
        var written = 0
        for (index in 0 until count) {
            if (layers[index].toInt() != layer) continue
            val base = index * 9
            val color = colors[index]
            for (corner in 0 until 3) {
                vertex(data, positions[base + corner * 3] - ox, positions[base + corner * 3 + 1] - oy, positions[base + corner * 3 + 2] - oz, color, alphaScale)
            }
            written += 3
        }
        submit(data, written, GL11.GL_TRIANGLES)
    }

    private fun lines(geometry: GizmoGeometry, layer: Int, alphaScale: Float, ox: Double, oy: Double, oz: Double) {
        val count = geometry.lineCount
        if (count == 0) return
        val positions = geometry.linePositions
        val colors = geometry.lineColors
        val widths = geometry.lineWidths
        val layers = geometry.lineLayers
        val buckets = LinkedHashMap<Float, MutableList<Int>>()
        for (index in 0 until count) {
            if (layers[index].toInt() != layer) continue
            buckets.getOrPut(widths[index]) { ArrayList() }.add(index)
        }
        for ((width, indices) in buckets) {
            GL11.glLineWidth(width)
            val data = reserve(indices.size * 2)
            for (index in indices) {
                val base = index * 6
                val color = colors[index]
                vertex(data, positions[base] - ox, positions[base + 1] - oy, positions[base + 2] - oz, color, alphaScale)
                vertex(data, positions[base + 3] - ox, positions[base + 4] - oy, positions[base + 5] - oz, color, alphaScale)
            }
            submit(data, indices.size * 2, GL11.GL_LINES)
        }
    }

    private fun reserve(vertexCount: Int): ByteBuffer {
        val needed = vertexCount * STRIDE
        if (vertices.capacity() < needed) vertices = BufferUtils.createByteBuffer(needed.coerceAtLeast(vertices.capacity() * 2))
        vertices.clear()
        return vertices
    }

    private fun vertex(data: ByteBuffer, x: Double, y: Double, z: Double, packed: Int, alphaScale: Float) {
        data.putFloat(x.toFloat()).putFloat(y.toFloat()).putFloat(z.toFloat())
        data.put((packed and 0xFF).toByte())
        data.put(((packed shr 8) and 0xFF).toByte())
        data.put(((packed shr 16) and 0xFF).toByte())
        data.put((((packed ushr 24) and 0xFF) * alphaScale).toInt().coerceIn(0, 255).toByte())
    }

    private fun submit(data: ByteBuffer, vertexCount: Int, mode: Int) {
        if (vertexCount == 0) return
        data.flip()
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, data, GL15.GL_STREAM_DRAW)
        GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, STRIDE, 0L)
        GL20.glVertexAttribPointer(1, 4, GL11.GL_UNSIGNED_BYTE, true, STRIDE, 12L)
        GL11.glDrawArrays(mode, 0, vertexCount)
    }

    override fun close() {
        surface.destroy()
        GL15.glDeleteBuffers(buffer)
        if (hasVao) GL30.glDeleteVertexArrays(vao)
        program.close()
    }

    private inner class MsaaSurface {
        private var disabled = false
        private var framebuffer = 0
        private var colorBuffer = 0
        private var depthBuffer = 0
        private var resolveFramebuffer = 0
        private var resolveTexture = 0
        private var width = 0
        private var height = 0
        private var depthFormat = 0

        fun begin(sourceFramebuffer: Int, viewport: Viewport): Boolean {
            if (disabled || viewport.isEmpty) return false
            if (!GL.getCapabilities().OpenGL30) return disable("OpenGL 3.0 framebuffer blits are unavailable")
            val format = sourceDepthFormat(sourceFramebuffer)
            if (format == 0) return disable("could not determine the depth format of framebuffer $sourceFramebuffer")
            if (!ensure(viewport.width, viewport.height, format)) return false
            drainErrors()
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, sourceFramebuffer)
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, framebuffer)
            GL30.glBlitFramebuffer(
                viewport.x, viewport.y, viewport.x + viewport.width, viewport.y + viewport.height,
                0, 0, viewport.width, viewport.height, GL11.GL_DEPTH_BUFFER_BIT, GL11.GL_NEAREST,
            )
            val error = GL11.glGetError()
            if (error != GL11.GL_NO_ERROR) {
                GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, sourceFramebuffer)
                return disable("depth blit failed with GL error 0x${Integer.toHexString(error)} (format 0x${Integer.toHexString(format)})")
            }
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer)
            GL11.glViewport(0, 0, viewport.width, viewport.height)
            GL11.glEnable(GL13.GL_MULTISAMPLE)
            GL11.glColorMask(true, true, true, true)
            GL11.glClearColor(0f, 0f, 0f, 0f)
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT)
            GL14.glBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA)
            return true
        }

        fun end(sourceFramebuffer: Int, viewport: Viewport) {
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, framebuffer)
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, resolveFramebuffer)
            GL30.glBlitFramebuffer(0, 0, viewport.width, viewport.height, 0, 0, viewport.width, viewport.height, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST)
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, sourceFramebuffer)
            GL11.glViewport(viewport.x, viewport.y, viewport.width, viewport.height)
            GL11.glDisable(GL11.GL_DEPTH_TEST)
            GL11.glDepthMask(false)
            GL11.glDisable(GL13.GL_MULTISAMPLE)
            GL11.glEnable(GL11.GL_BLEND)
            GL11.glBlendFunc(GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA)
            GL13.glActiveTexture(GL13.GL_TEXTURE0)
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, resolveTexture)
            GL20.glUseProgram(blit.id)
            GL20.glUniform1i(blit["tex"], 0)
            quad.draw()
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
            GL30.glFramebufferRenderbuffer(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL30.GL_RENDERBUFFER, colorBuffer)
            val depthAttachment = if (hasStencil(format)) GL30.GL_DEPTH_STENCIL_ATTACHMENT else GL30.GL_DEPTH_ATTACHMENT
            GL30.glFramebufferRenderbuffer(GL30.GL_FRAMEBUFFER, depthAttachment, GL30.GL_RENDERBUFFER, depthBuffer)
            val status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER)
            resolveTexture = GL11.glGenTextures()
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, resolveTexture)
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST)
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST)
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE)
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE)
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, w, h, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, null as ByteBuffer?)
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0)
            resolveFramebuffer = GL30.glGenFramebuffers()
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, resolveFramebuffer)
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, resolveTexture, 0)
            val resolveStatus = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER)
            val error = GL11.glGetError()
            if (status != GL30.GL_FRAMEBUFFER_COMPLETE || resolveStatus != GL30.GL_FRAMEBUFFER_COMPLETE || error != GL11.GL_NO_ERROR) {
                destroy()
                return disable(
                    "framebuffer incomplete (status 0x${Integer.toHexString(status)}, resolve 0x${Integer.toHexString(resolveStatus)}, error 0x${Integer.toHexString(error)})"
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
            val type = GL30.glGetFramebufferAttachmentParameteri(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE)
            if (type != GL30.GL_RENDERBUFFER && type != GL11.GL_TEXTURE) return 0
            val name = GL30.glGetFramebufferAttachmentParameteri(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME)
            return when (type) {
                GL30.GL_RENDERBUFFER -> {
                    GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, name)
                    val format = GL30.glGetRenderbufferParameteri(GL30.GL_RENDERBUFFER, GL30.GL_RENDERBUFFER_INTERNAL_FORMAT)
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
            if (!disabled) warn("gizmo anti-aliasing disabled: $reason")
            disabled = true
            destroy()
            return false
        }
    }

    companion object {
        const val WORLD = 0
        const val OVERLAY = 1
        const val OCCLUDED_ALPHA = 0.22f
        const val MAX_SAMPLES = 8
        const val STRIDE = 16

        const val VERTEX_SOURCE = """
uniform mat4 mvp;
attribute vec3 Position;
attribute vec4 Color;
varying vec4 color;
void main() {
    color = Color;
    gl_Position = mvp * vec4(Position, 1.0);
}
"""

        const val FRAGMENT_SOURCE = """
varying vec4 color;
void main() {
    gl_FragColor = color;
}
"""
    }
}
