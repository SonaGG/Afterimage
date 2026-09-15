package gg.sona.afterimage.gfx.gl

import gg.sona.afterimage.gfx.Blend
import gg.sona.afterimage.gfx.FullscreenPass
import gg.sona.afterimage.gfx.Target
import gg.sona.afterimage.gfx.Texture
import gg.sona.afterimage.gfx.Texture3D
import gg.sona.afterimage.gfx.Uniforms
import gg.sona.afterimage.gfx.Viewport
import org.lwjgl.BufferUtils
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL12
import org.lwjgl.opengl.GL13
import org.lwjgl.opengl.GL14
import org.lwjgl.opengl.GL15
import org.lwjgl.opengl.GL20
import org.lwjgl.opengl.GL30

class GlProgram(val id: Int, private val uniforms: Map<String, Int>) : AutoCloseable {
    operator fun get(name: String): Int = uniforms[name] ?: -1

    override fun close() = GL20.glDeleteProgram(id)

    companion object {
        fun link(vertex: String, fragment: String, uniforms: List<String>, attributes: List<String>): GlProgram {
            val vertexShader = compile(GL20.GL_VERTEX_SHADER, vertex)
            val fragmentShader = try {
                compile(GL20.GL_FRAGMENT_SHADER, fragment)
            } catch (error: Throwable) {
                GL20.glDeleteShader(vertexShader)
                throw error
            }
            val program = GL20.glCreateProgram()
            GL20.glAttachShader(program, vertexShader)
            GL20.glAttachShader(program, fragmentShader)
            attributes.forEachIndexed { index, name -> GL20.glBindAttribLocation(program, index, name) }
            GL20.glLinkProgram(program)
            GL20.glDetachShader(program, vertexShader)
            GL20.glDetachShader(program, fragmentShader)
            GL20.glDeleteShader(vertexShader)
            GL20.glDeleteShader(fragmentShader)
            if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
                val log = GL20.glGetProgramInfoLog(program, 4096)
                GL20.glDeleteProgram(program)
                throw IllegalStateException("shader link failed: $log")
            }
            return GlProgram(program, uniforms.associateWith { GL20.glGetUniformLocation(program, it) })
        }

        private fun compile(type: Int, source: String): Int {
            val shader = GL20.glCreateShader(type)
            GL20.glShaderSource(shader, source)
            GL20.glCompileShader(shader)
            if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
                val log = GL20.glGetShaderInfoLog(shader, 4096)
                GL20.glDeleteShader(shader)
                throw IllegalStateException("shader compile failed: $log")
            }
            return shader
        }
    }
}

class GlQuad : AutoCloseable {
    private val hasVao = GL.getCapabilities().OpenGL30
    private val buffer = GL15.glGenBuffers()
    private val vao = if (hasVao) GL30.glGenVertexArrays() else 0

    init {
        val vertices = BufferUtils.createFloatBuffer(24)
        vertices.put(floatArrayOf(
            -1f, -1f, 0f, 0f,
            1f, -1f, 1f, 0f,
            1f, 1f, 1f, 1f,
            -1f, -1f, 0f, 0f,
            1f, 1f, 1f, 1f,
            -1f, 1f, 0f, 1f,
        )).flip()
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, buffer)
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, vertices, GL15.GL_STATIC_DRAW)
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0)
    }

    fun draw() {
        if (hasVao) GL30.glBindVertexArray(vao)
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, buffer)
        GL20.glEnableVertexAttribArray(0)
        GL20.glEnableVertexAttribArray(1)
        GL20.glVertexAttribPointer(0, 2, GL11.GL_FLOAT, false, 16, 0L)
        GL20.glVertexAttribPointer(1, 2, GL11.GL_FLOAT, false, 16, 8L)
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 6)
        GL20.glDisableVertexAttribArray(0)
        GL20.glDisableVertexAttribArray(1)
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0)
        if (hasVao) GL30.glBindVertexArray(0)
    }

    override fun close() {
        GL15.glDeleteBuffers(buffer)
        if (hasVao) GL30.glDeleteVertexArrays(vao)
    }

    companion object {
        val ATTRIBUTES = listOf("Position", "UV")

        const val VERTEX_SOURCE = """
attribute vec2 Position;
attribute vec2 UV;
varying vec2 uv;
void main() {
    uv = UV;
    gl_Position = vec4(Position, 0.0, 1.0);
}
"""

        const val BLIT_SOURCE = """
uniform sampler2D tex;
varying vec2 uv;
void main() {
    gl_FragColor = texture2D(tex, uv);
}
"""
    }
}

class GlUniforms(private val program: GlProgram) : Uniforms {
    override fun texture(name: String, unit: Int, texture: Texture?) {
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit)
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture?.handle ?: 0)
        GL20.glUniform1i(program[name], unit)
    }

    override fun texture3D(name: String, unit: Int, texture: Texture3D?) {
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit)
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, texture?.handle ?: 0)
        GL20.glUniform1i(program[name], unit)
    }

    override fun int(name: String, value: Int) = GL20.glUniform1i(program[name], value)

    override fun float(name: String, value: Float) = GL20.glUniform1f(program[name], value)

    override fun vec2(name: String, x: Float, y: Float) = GL20.glUniform2f(program[name], x, y)
}

class GlFullscreenPass(
    private val state: GlState,
    private val quad: GlQuad,
    private val program: GlProgram,
) : FullscreenPass {
    private val uniforms = GlUniforms(program)

    override fun draw(target: Target?, viewport: Viewport, blend: Blend, bind: Uniforms.() -> Unit) {
        state.guarded {
            GlTarget.of(target).bind()
            GL11.glViewport(viewport.x, viewport.y, viewport.width, viewport.height)
            state.prepareShaderDraw()
            GL11.glDisable(GL11.GL_DEPTH_TEST)
            GL11.glDepthMask(false)
            applyBlend(blend)
            GL20.glUseProgram(program.id)
            uniforms.bind()
            GL13.glActiveTexture(GL13.GL_TEXTURE0)
            quad.draw()
        }
    }

    override fun close() = program.close()

    companion object {
        fun applyBlend(blend: Blend) {
            when (blend) {
                Blend.None -> GL11.glDisable(GL11.GL_BLEND)
                Blend.Alpha -> {
                    GL11.glEnable(GL11.GL_BLEND)
                    GL14.glBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA)
                }
                Blend.Premultiplied -> {
                    GL11.glEnable(GL11.GL_BLEND)
                    GL11.glBlendFunc(GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA)
                }
                is Blend.Accumulate -> {
                    GL11.glEnable(GL11.GL_BLEND)
                    GL14.glBlendColor(0f, 0f, 0f, blend.weight)
                    GL11.glBlendFunc(GL14.GL_CONSTANT_ALPHA, GL11.GL_ONE)
                }
            }
        }
    }
}
