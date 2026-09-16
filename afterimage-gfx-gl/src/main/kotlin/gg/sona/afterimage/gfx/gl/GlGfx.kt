package gg.sona.afterimage.gfx.gl

import gg.sona.afterimage.gfx.Blend
import gg.sona.afterimage.gfx.ColorMask
import gg.sona.afterimage.gfx.Filter
import gg.sona.afterimage.gfx.FullscreenPass
import gg.sona.afterimage.gfx.Gfx
import gg.sona.afterimage.gfx.GizmoPass
import gg.sona.afterimage.gfx.Readback
import gg.sona.afterimage.gfx.Target
import gg.sona.afterimage.gfx.Texture
import gg.sona.afterimage.gfx.Texture3D
import gg.sona.afterimage.gfx.TextureFormat
import gg.sona.afterimage.gfx.UiRenderer
import gg.sona.afterimage.gfx.Viewport
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL12
import org.lwjgl.opengl.GL13
import org.lwjgl.opengl.GL20
import org.lwjgl.opengl.GL30
import java.nio.ByteBuffer

class GlGfx(
    override val glslVersion: Int,
    private val legacy: Boolean,
    private val warn: (String) -> Unit = {},
) : Gfx, AutoCloseable {
    private val state = GlState(legacy)
    private val quadLazy = lazy { GlQuad() }
    private val quad by quadLazy
    private val blitLazy = lazy {
        GlProgram.link(Glsl.vertex(GlQuad.VERTEX_SOURCE, glslVersion), Glsl.fragment(GlQuad.BLIT_SOURCE, glslVersion), listOf("tex"), GlQuad.ATTRIBUTES)
    }
    private val blit by blitLazy

    fun wrap(framebuffer: Int, colorTexture: Int, width: Int, height: Int): GlTarget =
        GlTarget(framebuffer, if (colorTexture != 0) GlTexture(colorTexture, width, height, TextureFormat.RGBA8, owned = false) else null, width, height, owned = false)

    override fun createTexture(width: Int, height: Int, format: TextureFormat, filter: Filter): Texture =
        GlTexture.create(width, height, format, filter, null)

    override fun uploadTexture(width: Int, height: Int, rgba: ByteBuffer, filter: Filter, mipmaps: Boolean): Texture {
        val texture = GlTexture.create(width, height, TextureFormat.RGBA8, filter, rgba)
        if (mipmaps) texture.generateMipmaps()
        return texture
    }

    override fun uploadTextureLevels(width: Int, height: Int, levels: List<ByteBuffer>, filter: Filter): Texture {
        val texture = GlTexture.create(width, height, TextureFormat.RGBA8, filter, levels[0])
        val previous = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D)
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture.handle)
        var levelWidth = width
        var levelHeight = height
        for (level in 1 until levels.size) {
            levelWidth = maxOf(1, levelWidth / 2)
            levelHeight = maxOf(1, levelHeight / 2)
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, level, GL11.GL_RGBA8, levelWidth, levelHeight, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, levels[level])
        }
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL, levels.size - 1)
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, previous)
        return texture
    }

    override fun createTexture3D(size: Int, rgb: FloatArray): Texture3D = GlTexture3D.create(size, rgb)

    override fun createTarget(width: Int, height: Int, format: TextureFormat, filter: Filter): Target =
        GlTarget.create(width, height, format, filter)

    override fun createReadback(target: Target, slots: Int): Readback = GlReadback(target as GlTarget, slots)

    override fun createPass(fragmentSource: String, uniforms: List<String>): FullscreenPass {
        val program = GlProgram.link(
            Glsl.vertex(GlQuad.VERTEX_SOURCE, glslVersion),
            Glsl.fragment(fragmentSource, glslVersion),
            uniforms,
            GlQuad.ATTRIBUTES,
        )
        return GlFullscreenPass(state, quad, program)
    }

    override fun createUiRenderer(): UiRenderer = GlUiRenderer(state, glslVersion)

    override fun createGizmoPass(): GizmoPass = GlGizmoPass(state, quad, blit, legacy, glslVersion, warn)

    override fun blit(source: Texture, target: Target?, viewport: Viewport, blend: Blend, filter: Filter) {
        state.withState {
            GlTarget.of(target).bind()
            GL11.glViewport(viewport.x, viewport.y, viewport.width, viewport.height)
            state.prepareShaderDraw()
            GL11.glDisable(GL11.GL_DEPTH_TEST)
            GL11.glDepthMask(false)
            GlFullscreenPass.applyBlend(blend)
            GL13.glActiveTexture(GL13.GL_TEXTURE0)
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, source.handle)
            if (filter == Filter.LINEAR_MIPMAP) GL30.glGenerateMipmap(GL11.GL_TEXTURE_2D)
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GlTexture.minFilter(filter))
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GlTexture.magFilter(filter))
            GL20.glUseProgram(blit.id)
            GL20.glUniform1i(blit["tex"], 0)
            quad.draw()
        }
    }

    override fun copyColor(source: Target?, x: Int, y: Int, width: Int, height: Int, into: Texture) {
        val previousRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING)
        val previousTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D)
        GlTarget.of(source).bind(GL30.GL_READ_FRAMEBUFFER)
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, into.handle)
        GL11.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, x, y, width, height)
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousTexture)
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, previousRead)
    }

    override fun copyDepth(source: Target?, x: Int, y: Int, width: Int, height: Int, into: Texture): Boolean {
        val previousRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING)
        val previousTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D)
        GlTarget.of(source).bind(GL30.GL_READ_FRAMEBUFFER)
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, into.handle)
        while (GL11.glGetError() != GL11.GL_NO_ERROR) Unit
        GL11.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, x, y, width, height)
        val error = GL11.glGetError()
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousTexture)
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, previousRead)
        if (error != GL11.GL_NO_ERROR) {
            warn("depth capture disabled: GL error 0x${Integer.toHexString(error)}")
            return false
        }
        return true
    }

    override fun readPixels(source: Target?, x: Int, y: Int, width: Int, height: Int, into: ByteBuffer) {
        val previousRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING)
        val alignment = GL11.glGetInteger(GL11.GL_PACK_ALIGNMENT)
        GlTarget.of(source).bind(GL30.GL_READ_FRAMEBUFFER)
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1)
        GL11.glReadPixels(x, y, width, height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, into)
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, alignment)
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, previousRead)
    }

    override fun clear(target: Target?, red: Float, green: Float, blue: Float, alpha: Float, mask: ColorMask, depth: Boolean) {
        state.withState {
            GlTarget.of(target).bind()
            GL11.glDisable(GL11.GL_SCISSOR_TEST)
            if (mask == ColorMask.ALPHA) GL11.glColorMask(false, false, false, true) else GL11.glColorMask(true, true, true, true)
            GL11.glClearColor(red, green, blue, alpha)
            if (depth) GL11.glDepthMask(true)
            GL11.glClear(if (depth) GL11.GL_COLOR_BUFFER_BIT or GL11.GL_DEPTH_BUFFER_BIT else GL11.GL_COLOR_BUFFER_BIT)
        }
    }

    override fun <T> preservingTarget(block: () -> T): T {
        val draw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING)
        val read = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING)
        try {
            return block()
        } finally {
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, draw)
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, read)
        }
    }

    override fun close() {
        if (blitLazy.isInitialized()) runCatching { blit.close() }
        if (quadLazy.isInitialized()) runCatching { quad.close() }
    }
}
