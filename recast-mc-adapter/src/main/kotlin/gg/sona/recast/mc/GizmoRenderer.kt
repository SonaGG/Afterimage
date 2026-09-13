package gg.sona.recast.mc

import gg.sona.recast.editor.host.GizmoBatch
import net.minecraft.client.Minecraft
import org.lwjgl.BufferUtils
import org.lwjgl.opengl.*

class GizmoRenderer(private val minecraft: Minecraft) {
    var batch: () -> GizmoBatch? = { null }
    var enabled: () -> Boolean = { false }

    private val surface = GizmoSurface()
    private val viewport = BufferUtils.createIntBuffer(16)
    private var originX = 0.0
    private var originY = 0.0
    private var originZ = 0.0

    fun render(tickDelta: Float) {
        if (!enabled()) return
        val current = batch() ?: return
        if (current.isEmpty) return
        val view = minecraft.camera ?: return
        val originX = view.lastX + (view.x - view.lastX) * tickDelta
        val originY = view.lastY + (view.y - view.lastY) * tickDelta
        val originZ = view.lastZ + (view.z - view.lastZ) * tickDelta

        val program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM)
        val framebuffer =
            if (GL.getCapabilities().OpenGL30) GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING) else 0
        viewport.clear()
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport)
        val viewX = viewport.get(0)
        val viewY = viewport.get(1)
        val viewWidth = viewport.get(2)
        val viewHeight = viewport.get(3)
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS)
        GL11.glPushClientAttrib(GL11.GL_CLIENT_ALL_ATTRIB_BITS)
        GL11.glMatrixMode(GL11.GL_MODELVIEW)
        GL11.glPushMatrix()
        var offscreen = false
        try {
            if (program != 0) GL20.glUseProgram(0)
            GL13.glActiveTexture(GL13.GL_TEXTURE1)
            GL11.glDisable(GL11.GL_TEXTURE_2D)
            GL13.glActiveTexture(GL13.GL_TEXTURE0)
            GL11.glDisable(GL11.GL_TEXTURE_2D)
            GL11.glDisable(GL11.GL_LIGHTING)
            GL11.glDisable(GL11.GL_FOG)
            GL11.glDisable(GL11.GL_ALPHA_TEST)
            GL11.glDisable(GL11.GL_CULL_FACE)
            GL11.glDisable(GL11.GL_COLOR_MATERIAL)
            GL11.glDisable(GL11.GL_SCISSOR_TEST)
            GL11.glEnable(GL11.GL_BLEND)
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA)
            GL11.glShadeModel(GL11.GL_FLAT)
            this.originX = originX
            this.originY = originY
            this.originZ = originZ

            offscreen = surface.begin(framebuffer, viewX, viewY, viewWidth, viewHeight)
            if (!offscreen) {
                GL11.glEnable(GL11.GL_LINE_SMOOTH)
                GL11.glHint(GL11.GL_LINE_SMOOTH_HINT, GL11.GL_NICEST)
            }

            GL11.glEnable(GL11.GL_DEPTH_TEST)
            GL11.glDepthMask(false)
            GL11.glDepthFunc(GL11.GL_GREATER)
            triangles(current, GizmoBatch.WORLD, OCCLUDED_ALPHA)
            lines(current, GizmoBatch.WORLD, OCCLUDED_ALPHA)

            GL11.glDepthFunc(GL11.GL_LEQUAL)
            GL11.glDepthMask(true)
            triangles(current, GizmoBatch.WORLD, 1f)
            GL11.glDepthMask(false)
            lines(current, GizmoBatch.WORLD, 1f)

            GL11.glDepthMask(true)
            GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT)
            triangles(current, GizmoBatch.OVERLAY, 1f)
            GL11.glDepthMask(false)
            lines(current, GizmoBatch.OVERLAY, 1f)

            if (offscreen) surface.end(framebuffer, viewX, viewY, viewWidth, viewHeight)
        } finally {
            if (offscreen) GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer)
            GL11.glPopMatrix()
            GL11.glPopClientAttrib()
            GL11.glPopAttrib()
            if (program != 0) GL20.glUseProgram(program)
        }
    }

    private fun triangles(batch: GizmoBatch, layer: Int, alphaScale: Float) {
        val count = batch.triCount
        if (count == 0) return
        val positions = batch.triPositions
        val colors = batch.triColors
        val layers = batch.triLayers
        var open = false
        for (index in 0 until count) {
            if (layers[index].toInt() != layer) continue
            if (!open) {
                GL11.glBegin(GL11.GL_TRIANGLES)
                open = true
            }
            color(colors[index], alphaScale)
            val base = index * 9
            vertex(positions[base], positions[base + 1], positions[base + 2])
            vertex(positions[base + 3], positions[base + 4], positions[base + 5])
            vertex(positions[base + 6], positions[base + 7], positions[base + 8])
        }
        if (open) GL11.glEnd()
    }

    private fun lines(batch: GizmoBatch, layer: Int, alphaScale: Float) {
        val count = batch.lineCount
        if (count == 0) return
        val positions = batch.linePositions
        val colors = batch.lineColors
        val widths = batch.lineWidths
        val layers = batch.lineLayers
        val buckets = LinkedHashMap<Float, MutableList<Int>>()
        for (index in 0 until count) {
            if (layers[index].toInt() != layer) continue
            buckets.getOrPut(widths[index]) { ArrayList() }.add(index)
        }
        for ((width, indices) in buckets) {
            GL11.glLineWidth(width)
            GL11.glBegin(GL11.GL_LINES)
            for (index in indices) {
                color(colors[index], alphaScale)
                val base = index * 6
                vertex(positions[base], positions[base + 1], positions[base + 2])
                vertex(positions[base + 3], positions[base + 4], positions[base + 5])
            }
            GL11.glEnd()
        }
    }

    private fun vertex(x: Double, y: Double, z: Double) =
        GL11.glVertex3f((x - originX).toFloat(), (y - originY).toFloat(), (z - originZ).toFloat())

    private fun color(packed: Int, alphaScale: Float) {
        val r = (packed and 0xFF) / 255f
        val g = ((packed shr 8) and 0xFF) / 255f
        val b = ((packed shr 16) and 0xFF) / 255f
        val a = ((packed ushr 24) and 0xFF) / 255f * alphaScale
        GL11.glColor4f(r, g, b, a)
    }

    private companion object {
        const val OCCLUDED_ALPHA = 0.22f
    }
}
