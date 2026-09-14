package gg.sona.afterimage.mc.ui

import imgui.ImDrawData
import imgui.ImGui
import imgui.ImVec4
import imgui.type.ImInt
import net.minecraft.client.render.platform.GlStateManager
import org.lwjgl.BufferUtils
import org.lwjgl.opengl.*
import java.nio.ByteBuffer

class ImGuiRenderer {
    private var program = 0
    private var vertexShader = 0
    private var fragmentShader = 0
    private var vertexBuffer = 0
    private var indexBuffer = 0

    private var attributePosition = 0
    private var attributeUv = 0
    private var attributeColour = 0
    private var uniformProjection = 0
    private var uniformTexture = 0

    private var fontTexture = 0
    private var started = false

    private val clip = ImVec4()
    private val projection = BufferUtils.createFloatBuffer(16)

    fun start() {
        if (started) return

        vertexShader = compile(GL20.GL_VERTEX_SHADER, VERTEX_SOURCE)
        fragmentShader = compile(GL20.GL_FRAGMENT_SHADER, FRAGMENT_SOURCE)

        program = GL20.glCreateProgram()
        GL20.glAttachShader(program, vertexShader)
        GL20.glAttachShader(program, fragmentShader)
        GL20.glBindAttribLocation(program, POSITION_LOCATION, "Position")
        GL20.glBindAttribLocation(program, UV_LOCATION, "UV")
        GL20.glBindAttribLocation(program, COLOUR_LOCATION, "Color")
        GL20.glLinkProgram(program)
        check(GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) != GL11.GL_FALSE) {
            "Afterimage ImGui shader link failed: " + GL20.glGetProgramInfoLog(program)
        }

        attributePosition = GL20.glGetAttribLocation(program, "Position")
        attributeUv = GL20.glGetAttribLocation(program, "UV")
        attributeColour = GL20.glGetAttribLocation(program, "Color")
        uniformProjection = GL20.glGetUniformLocation(program, "ProjMtx")
        uniformTexture = GL20.glGetUniformLocation(program, "Texture")

        vertexBuffer = GL15.glGenBuffers()
        indexBuffer = GL15.glGenBuffers()
        started = true
    }

    fun createFontAtlas() {
        val width = ImInt()
        val height = ImInt()
        val pixels: ByteBuffer = ImGui.getIO().fonts.getTexDataAsRGBA32(width, height)

        if (fontTexture != 0) GL11.glDeleteTextures(fontTexture)
        fontTexture = GL11.glGenTextures()

        GlStateManager.activeTexture(GL13.GL_TEXTURE0)
        GlStateManager.bindTexture(fontTexture)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE)
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4)
        GL11.glTexImage2D(
            GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, width.get(), height.get(), 0,
            GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels,
        )
        GlStateManager.bindTexture(0)

        ImGui.getIO().fonts.setTexID(fontTexture.toLong())
    }

    fun destroy() {
        if (fontTexture != 0) {
            GL11.glDeleteTextures(fontTexture)
            fontTexture = 0
        }
        if (!started) return
        started = false
        GL15.glDeleteBuffers(vertexBuffer)
        GL15.glDeleteBuffers(indexBuffer)
        GL20.glDetachShader(program, vertexShader)
        GL20.glDetachShader(program, fragmentShader)
        GL20.glDeleteShader(vertexShader)
        GL20.glDeleteShader(fragmentShader)
        GL20.glDeleteProgram(program)
        program = 0
    }

    fun render(data: ImDrawData, framebufferWidth: Int, framebufferHeight: Int) {
        if (!started || !data.valid || data.cmdListsCount == 0) return
        if (framebufferWidth <= 0 || framebufferHeight <= 0) return

        beginState(framebufferWidth, framebufferHeight)
        try {
            drawLists(data, framebufferHeight)
        } finally {
            endState()
        }
    }

    private fun beginState(width: Int, height: Int) {
        GlStateManager.activeTexture(GL13.GL_TEXTURE1)
        GlStateManager.disableTexture()
        GlStateManager.activeTexture(GL13.GL_TEXTURE0)
        GlStateManager.enableTexture()

        GlStateManager.enableBlend()
        GlStateManager.blendFuncSeparate(
            GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
            GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA,
        )
        GlStateManager.disableAlphaTest()
        GlStateManager.disableDepthTest()
        GlStateManager.disableLighting()
        GlStateManager.disableCull()
        GlStateManager.color4f(1f, 1f, 1f, 1f)

        GL13.glClientActiveTexture(GL13.GL_TEXTURE1)
        GL11.glDisableClientState(GL11.GL_TEXTURE_COORD_ARRAY)
        GL13.glClientActiveTexture(GL13.GL_TEXTURE0)
        GL11.glDisableClientState(GL11.GL_TEXTURE_COORD_ARRAY)
        GL11.glDisableClientState(GL11.GL_VERTEX_ARRAY)
        GL11.glDisableClientState(GL11.GL_COLOR_ARRAY)
        GL11.glDisableClientState(GL11.GL_NORMAL_ARRAY)
        GlStateManager.disableFog()
        GlStateManager.disableColorMaterial()
        GlStateManager.depthMask(true)

        GL11.glEnable(GL11.GL_SCISSOR_TEST)
        GL11.glViewport(0, 0, width, height)

        GL20.glUseProgram(program)
        GL20.glUniform1i(uniformTexture, 0)

        orthographic(width.toFloat(), height.toFloat())
        GL20.glUniformMatrix4fv(uniformProjection, false, projection)

        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vertexBuffer)
        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, indexBuffer)
        GL20.glEnableVertexAttribArray(attributePosition)
        GL20.glEnableVertexAttribArray(attributeUv)
        GL20.glEnableVertexAttribArray(attributeColour)
    }

    private fun endState() {
        GL20.glDisableVertexAttribArray(attributePosition)
        GL20.glDisableVertexAttribArray(attributeUv)
        GL20.glDisableVertexAttribArray(attributeColour)
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0)
        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, 0)
        GL20.glUseProgram(0)

        GL11.glDisable(GL11.GL_SCISSOR_TEST)

        GlStateManager.bindTexture(0)
        GlStateManager.enableDepthTest()
        GlStateManager.enableAlphaTest()
        GlStateManager.disableBlend()
        GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA)
        GlStateManager.color4f(1f, 1f, 1f, 1f)
        GlStateManager.activeTexture(GL13.GL_TEXTURE1)
        GlStateManager.disableTexture()
        GlStateManager.activeTexture(GL13.GL_TEXTURE0)
        GlStateManager.enableTexture()
    }

    private fun drawLists(data: ImDrawData, framebufferHeight: Int) {
        val stride = ImDrawData.sizeOfImDrawVert()
        val offsetX = data.displayPosX
        val offsetY = data.displayPosY
        val scaleX = data.framebufferScaleX
        val scaleY = data.framebufferScaleY

        for (list in 0 until data.cmdListsCount) {
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, data.getCmdListVtxBufferData(list), GL15.GL_STREAM_DRAW)
            GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, data.getCmdListIdxBufferData(list), GL15.GL_STREAM_DRAW)

            for (command in 0 until data.getCmdListCmdBufferSize(list)) {
                val elements = data.getCmdListCmdBufferElemCount(list, command)
                if (elements == 0) continue

                data.getCmdListCmdBufferClipRect(clip, list, command)
                val left = ((clip.x - offsetX) * scaleX).toInt()
                val right = ((clip.z - offsetX) * scaleX).toInt()
                val top = ((clip.y - offsetY) * scaleY).toInt()
                val bottom = ((clip.w - offsetY) * scaleY).toInt()
                if (right <= left || bottom <= top) continue
                GL11.glScissor(left, framebufferHeight - bottom, right - left, bottom - top)

                val base = data.getCmdListCmdBufferVtxOffset(list, command).toLong() * stride
                GL20.glVertexAttribPointer(attributePosition, 2, GL11.GL_FLOAT, false, stride, base + POSITION_OFFSET)
                GL20.glVertexAttribPointer(attributeUv, 2, GL11.GL_FLOAT, false, stride, base + UV_OFFSET)
                GL20.glVertexAttribPointer(
                    attributeColour,
                    4,
                    GL11.GL_UNSIGNED_BYTE,
                    true,
                    stride,
                    base + COLOUR_OFFSET
                )

                GlStateManager.bindTexture(data.getCmdListCmdBufferTextureId(list, command).toInt())

                GL11.glDrawElements(
                    GL11.GL_TRIANGLES,
                    elements,
                    GL11.GL_UNSIGNED_SHORT,
                    data.getCmdListCmdBufferIdxOffset(list, command).toLong() * INDEX_BYTES,
                )
            }
        }
    }

    private fun orthographic(width: Float, height: Float) {
        projection.clear()
        projection.put(2f / width).put(0f).put(0f).put(0f)
        projection.put(0f).put(-2f / height).put(0f).put(0f)
        projection.put(0f).put(0f).put(-1f).put(0f)
        projection.put(-1f).put(1f).put(0f).put(1f)
        projection.flip()
    }

    private fun compile(type: Int, source: String): Int {
        val shader = GL20.glCreateShader(type)
        GL20.glShaderSource(shader, source)
        GL20.glCompileShader(shader)
        check(GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) != GL11.GL_FALSE) {
            "Afterimage ImGui shader failed to compile: " + GL20.glGetShaderInfoLog(shader)
        }
        return shader
    }

    private companion object {
        const val POSITION_LOCATION = 0
        const val UV_LOCATION = 1
        const val COLOUR_LOCATION = 2
        const val POSITION_OFFSET = 0L
        const val UV_OFFSET = 8L
        const val COLOUR_OFFSET = 16L
        const val INDEX_BYTES = 2L

        val VERTEX_SOURCE = listOf(
            "#version 120",
            "uniform mat4 ProjMtx;",
            "attribute vec2 Position;",
            "attribute vec2 UV;",
            "attribute vec4 Color;",
            "varying vec2 Frag_UV;",
            "varying vec4 Frag_Color;",
            "void main() {",
            "    Frag_UV = UV;",
            "    Frag_Color = Color;",
            "    gl_Position = ProjMtx * vec4(Position.xy, 0, 1);",
            "}",
        ).joinToString("\n")

        val FRAGMENT_SOURCE = listOf(
            "#version 120",
            "uniform sampler2D Texture;",
            "varying vec2 Frag_UV;",
            "varying vec4 Frag_Color;",
            "void main() {",
            "    gl_FragColor = Frag_Color * texture2D(Texture, Frag_UV.st);",
            "}",
        ).joinToString("\n")
    }
}
