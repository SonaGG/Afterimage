package gg.sona.afterimage.gfx.gl

import org.lwjgl.BufferUtils
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL12
import org.lwjgl.opengl.GL13
import org.lwjgl.opengl.GL14
import org.lwjgl.opengl.GL15
import org.lwjgl.opengl.GL20
import org.lwjgl.opengl.GL30

class GlState(private val legacy: Boolean) {
    private val ints = BufferUtils.createIntBuffer(16)
    private val floats = BufferUtils.createFloatBuffer(16)
    private val hasVao = GL.getCapabilities().OpenGL30

    private var program = 0
    private var vao = 0
    private var arrayBuffer = 0
    private var elementBuffer = 0
    private var activeTexture = 0
    private val textures2D = IntArray(UNITS)
    private var texture3D = 0
    private var drawFramebuffer = 0
    private var readFramebuffer = 0
    private val viewport = IntArray(4)
    private val scissorBox = IntArray(4)
    private var scissor = false
    private var blend = false
    private var blendSrcRgb = 0
    private var blendDstRgb = 0
    private var blendSrcAlpha = 0
    private var blendDstAlpha = 0
    private val blendColor = FloatArray(4)
    private var depthTest = false
    private var depthMask = false
    private var depthFunc = 0
    private var cull = false
    private val colorMask = BooleanArray(4)
    private val clearColor = FloatArray(4)
    private var lineWidth = 1f
    private var multisample = false
    private var packAlignment = 4
    private var unpackAlignment = 4

    fun push() {
        if (legacy) {
            GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS)
            GL11.glPushClientAttrib(GL11.GL_CLIENT_ALL_ATTRIB_BITS)
        }
        program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM)
        vao = if (hasVao) GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING) else 0
        arrayBuffer = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING)
        elementBuffer = GL11.glGetInteger(GL15.GL_ELEMENT_ARRAY_BUFFER_BINDING)
        activeTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE)
        for (unit in 0 until UNITS) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit)
            textures2D[unit] = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D)
        }
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + LUT_UNIT)
        texture3D = GL11.glGetInteger(GL12.GL_TEXTURE_BINDING_3D)
        GL13.glActiveTexture(activeTexture)
        drawFramebuffer = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING)
        readFramebuffer = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING)
        ints.clear()
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, ints)
        for (i in 0 until 4) viewport[i] = ints.get(i)
        ints.clear()
        GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, ints)
        for (i in 0 until 4) scissorBox[i] = ints.get(i)
        scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST)
        blend = GL11.glIsEnabled(GL11.GL_BLEND)
        blendSrcRgb = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB)
        blendDstRgb = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB)
        blendSrcAlpha = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA)
        blendDstAlpha = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA)
        floats.clear()
        GL11.glGetFloatv(GL14.GL_BLEND_COLOR, floats)
        for (i in 0 until 4) blendColor[i] = floats.get(i)
        depthTest = GL11.glIsEnabled(GL11.GL_DEPTH_TEST)
        depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK)
        depthFunc = GL11.glGetInteger(GL11.GL_DEPTH_FUNC)
        cull = GL11.glIsEnabled(GL11.GL_CULL_FACE)
        ints.clear()
        GL11.glGetIntegerv(GL11.GL_COLOR_WRITEMASK, ints)
        for (i in 0 until 4) colorMask[i] = ints.get(i) != 0
        floats.clear()
        GL11.glGetFloatv(GL11.GL_COLOR_CLEAR_VALUE, floats)
        for (i in 0 until 4) clearColor[i] = floats.get(i)
        lineWidth = GL11.glGetFloat(GL11.GL_LINE_WIDTH)
        multisample = GL11.glIsEnabled(GL13.GL_MULTISAMPLE)
        packAlignment = GL11.glGetInteger(GL11.GL_PACK_ALIGNMENT)
        unpackAlignment = GL11.glGetInteger(GL11.GL_UNPACK_ALIGNMENT)
    }

    fun pop() {
        GL20.glUseProgram(program)
        if (hasVao) GL30.glBindVertexArray(vao)
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, arrayBuffer)
        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, elementBuffer)
        for (unit in 0 until UNITS) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit)
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, textures2D[unit])
        }
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + LUT_UNIT)
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, texture3D)
        GL13.glActiveTexture(activeTexture)
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, drawFramebuffer)
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readFramebuffer)
        GL11.glViewport(viewport[0], viewport[1], viewport[2], viewport[3])
        GL11.glScissor(scissorBox[0], scissorBox[1], scissorBox[2], scissorBox[3])
        toggle(GL11.GL_SCISSOR_TEST, scissor)
        toggle(GL11.GL_BLEND, blend)
        GL14.glBlendFuncSeparate(blendSrcRgb, blendDstRgb, blendSrcAlpha, blendDstAlpha)
        GL14.glBlendColor(blendColor[0], blendColor[1], blendColor[2], blendColor[3])
        toggle(GL11.GL_DEPTH_TEST, depthTest)
        GL11.glDepthMask(depthMask)
        GL11.glDepthFunc(depthFunc)
        toggle(GL11.GL_CULL_FACE, cull)
        GL11.glColorMask(colorMask[0], colorMask[1], colorMask[2], colorMask[3])
        GL11.glClearColor(clearColor[0], clearColor[1], clearColor[2], clearColor[3])
        GL11.glLineWidth(lineWidth)
        toggle(GL13.GL_MULTISAMPLE, multisample)
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, packAlignment)
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, unpackAlignment)
        if (legacy) {
            GL11.glPopClientAttrib()
            GL11.glPopAttrib()
        }
    }

    fun prepareShaderDraw() {
        if (legacy) {
            GL11.glDisable(GL11.GL_ALPHA_TEST)
            GL11.glDisable(GL11.GL_LIGHTING)
            GL11.glDisable(GL11.GL_FOG)
            GL11.glDisable(GL11.GL_COLOR_MATERIAL)
            GL13.glActiveTexture(GL13.GL_TEXTURE1)
            GL11.glDisable(GL11.GL_TEXTURE_2D)
            GL13.glActiveTexture(GL13.GL_TEXTURE0)
            GL11.glEnable(GL11.GL_TEXTURE_2D)
        }
        GL11.glDisable(GL11.GL_CULL_FACE)
        GL11.glDisable(GL11.GL_SCISSOR_TEST)
        GL11.glColorMask(true, true, true, true)
    }

    fun <T> guarded(block: () -> T): T {
        push()
        try {
            return block()
        } finally {
            pop()
        }
    }

    companion object {
        const val UNITS = 3
        const val LUT_UNIT = 2

        fun toggle(capability: Int, enabled: Boolean) {
            if (enabled) GL11.glEnable(capability) else GL11.glDisable(capability)
        }
    }
}
