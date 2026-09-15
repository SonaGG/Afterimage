package gg.sona.afterimage.gfx.gl

import gg.sona.afterimage.gfx.Target
import gg.sona.afterimage.gfx.UiRenderer
import org.lwjgl.BufferUtils
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL13
import org.lwjgl.opengl.GL14
import org.lwjgl.opengl.GL15
import org.lwjgl.opengl.GL20
import org.lwjgl.opengl.GL30
import java.nio.ByteBuffer

class GlUiRenderer(private val state: GlState, glslVersion: Int) : UiRenderer {
    private val hasVao = GL.getCapabilities().OpenGL30
    private val program = GlProgram.link(
        Glsl.vertex(VERTEX_SOURCE, glslVersion),
        Glsl.fragment(FRAGMENT_SOURCE, glslVersion),
        listOf("ProjMtx", "Texture"),
        listOf("Position", "UV", "Color"),
    )
    private val vertexBuffer = GL15.glGenBuffers()
    private val indexBuffer = GL15.glGenBuffers()
    private val vao = if (hasVao) GL30.glGenVertexArrays() else 0
    private val projection = BufferUtils.createFloatBuffer(16)
    private var framebufferHeight = 0
    private var lastTexture = -1

    override fun begin(target: Target?, framebufferWidth: Int, framebufferHeight: Int) {
        this.framebufferHeight = framebufferHeight
        lastTexture = -1
        state.push()
        GlTarget.of(target).bind()
        state.prepareShaderDraw()
        GL11.glViewport(0, 0, framebufferWidth, framebufferHeight)
        GL11.glEnable(GL11.GL_BLEND)
        GL14.glBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA)
        GL11.glDisable(GL11.GL_DEPTH_TEST)
        GL11.glDepthMask(false)
        GL11.glEnable(GL11.GL_SCISSOR_TEST)
        GL20.glUseProgram(program.id)
        GL20.glUniform1i(program["Texture"], 0)
        orthographic(framebufferWidth.toFloat(), framebufferHeight.toFloat())
        GL20.glUniformMatrix4fv(program["ProjMtx"], false, projection)
        if (hasVao) GL30.glBindVertexArray(vao)
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vertexBuffer)
        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, indexBuffer)
        GL20.glEnableVertexAttribArray(0)
        GL20.glEnableVertexAttribArray(1)
        GL20.glEnableVertexAttribArray(2)
        GL13.glActiveTexture(GL13.GL_TEXTURE0)
    }

    override fun uploadVertices(vertices: ByteBuffer) {
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, vertices, GL15.GL_STREAM_DRAW)
    }

    override fun uploadIndices(indices: ByteBuffer) {
        GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, indices, GL15.GL_STREAM_DRAW)
    }

    override fun draw(clipX: Int, clipY: Int, clipWidth: Int, clipHeight: Int, texture: Int, elements: Int, indexOffset: Int, vertexOffset: Int) {
        if (clipWidth <= 0 || clipHeight <= 0 || elements == 0) return
        GL11.glScissor(clipX, framebufferHeight - clipY - clipHeight, clipWidth, clipHeight)
        val base = vertexOffset.toLong() * STRIDE
        GL20.glVertexAttribPointer(0, 2, GL11.GL_FLOAT, false, STRIDE, base)
        GL20.glVertexAttribPointer(1, 2, GL11.GL_FLOAT, false, STRIDE, base + 8L)
        GL20.glVertexAttribPointer(2, 4, GL11.GL_UNSIGNED_BYTE, true, STRIDE, base + 16L)
        if (texture != lastTexture) {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture)
            lastTexture = texture
        }
        GL11.glDrawElements(GL11.GL_TRIANGLES, elements, GL11.GL_UNSIGNED_SHORT, indexOffset.toLong() * 2L)
    }

    override fun end() {
        GL20.glDisableVertexAttribArray(0)
        GL20.glDisableVertexAttribArray(1)
        GL20.glDisableVertexAttribArray(2)
        if (hasVao) GL30.glBindVertexArray(0)
        state.pop()
    }

    override fun close() {
        GL15.glDeleteBuffers(vertexBuffer)
        GL15.glDeleteBuffers(indexBuffer)
        if (hasVao) GL30.glDeleteVertexArrays(vao)
        program.close()
    }

    private fun orthographic(width: Float, height: Float) {
        projection.clear()
        projection.put(2f / width).put(0f).put(0f).put(0f)
        projection.put(0f).put(-2f / height).put(0f).put(0f)
        projection.put(0f).put(0f).put(-1f).put(0f)
        projection.put(-1f).put(1f).put(0f).put(1f)
        projection.flip()
    }

    companion object {
        const val STRIDE = 20

        const val VERTEX_SOURCE = """
uniform mat4 ProjMtx;
attribute vec2 Position;
attribute vec2 UV;
attribute vec4 Color;
varying vec2 Frag_UV;
varying vec4 Frag_Color;
void main() {
    Frag_UV = UV;
    Frag_Color = Color;
    gl_Position = ProjMtx * vec4(Position.xy, 0, 1);
}
"""

        const val FRAGMENT_SOURCE = """
uniform sampler2D Texture;
varying vec2 Frag_UV;
varying vec4 Frag_Color;
void main() {
    gl_FragColor = Frag_Color * texture2D(Texture, Frag_UV.st);
}
"""
    }
}
