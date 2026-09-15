package gg.sona.afterimage.mc

import gg.sona.afterimage.editor.host.GizmoBatch
import gg.sona.afterimage.gfx.GizmoPass
import gg.sona.afterimage.gfx.Viewport
import net.minecraft.client.Minecraft
import org.joml.Matrix4f
import org.lwjgl.BufferUtils
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL30

class GizmoRenderer(private val minecraft: Minecraft) {
    var batch: () -> GizmoBatch? = { null }
    var enabled: () -> Boolean = { false }

    private val gfx = McGfx.gfx
    private var pass: GizmoPass? = null
    private val buffer = BufferUtils.createFloatBuffer(16)
    private val viewport = BufferUtils.createIntBuffer(16)
    private val projection = Matrix4f()
    private val modelView = Matrix4f()
    private val product = Matrix4f()
    private val viewProjection = FloatArray(16)

    fun render(tickDelta: Float) {
        if (!enabled()) return
        val current = batch() ?: return
        if (current.isEmpty) return
        val view = minecraft.camera ?: return
        val originX = view.lastX + (view.x - view.lastX) * tickDelta
        val originY = view.lastY + (view.y - view.lastY) * tickDelta
        val originZ = view.lastZ + (view.z - view.lastZ) * tickDelta

        buffer.clear()
        GL11.glGetFloatv(GL11.GL_PROJECTION_MATRIX, buffer)
        projection.set(buffer)
        buffer.clear()
        GL11.glGetFloatv(GL11.GL_MODELVIEW_MATRIX, buffer)
        modelView.set(buffer)
        projection.mul(modelView, product).get(viewProjection)
        viewport.clear()
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport)
        val framebuffer = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING)
        val target = gfx.wrap(framebuffer, 0, viewport.get(2), viewport.get(3))
        val pass = pass ?: gfx.createGizmoPass().also { pass = it }
        pass.draw(
            target,
            Viewport(viewport.get(0), viewport.get(1), viewport.get(2), viewport.get(3)),
            viewProjection,
            originX,
            originY,
            originZ,
            current,
        )
    }

    fun release() {
        pass?.close()
        pass = null
    }
}
