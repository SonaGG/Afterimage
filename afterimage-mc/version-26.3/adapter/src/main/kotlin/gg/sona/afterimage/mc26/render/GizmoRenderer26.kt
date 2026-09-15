package gg.sona.afterimage.mc26.render

import gg.sona.afterimage.editor.host.GizmoBatch
import gg.sona.afterimage.gfx.GizmoPass
import gg.sona.afterimage.gfx.Viewport
import gg.sona.afterimage.mc26.McGfx26
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.state.level.CameraRenderState
import org.joml.Matrix4f

class GizmoRenderer26(private val minecraft: Minecraft) {
    var batch: () -> GizmoBatch? = { null }
    var enabled: () -> Boolean = { false }
    var viewport: () -> IntArray? = { null }
    var viewProjection: ((FloatArray) -> FloatArray)? = null
    private val gfx = McGfx26.gfx
    private var pass: GizmoPass? = null
    private val product = Matrix4f()
    private val matrix = FloatArray(16)

    fun render(camera: CameraRenderState) {
        if (!enabled() || !camera.initialized) return
        val current = batch() ?: return
        if (current.isEmpty) return
        val viewProjection = viewProjection?.invoke(matrix) ?: camera.projectionMatrix.mul(camera.viewRotationMatrix, product).get(matrix)
        val target = McGfx26.mainTarget(minecraft)
        val rect = viewport() ?: intArrayOf(0, 0, target.width, target.height)
        val pass = pass ?: gfx.createGizmoPass().also { pass = it }
        pass.draw(target, Viewport(rect[0], rect[1], rect[2], rect[3]), viewProjection, camera.pos.x, camera.pos.y, camera.pos.z, current)
    }

    fun release() {
        pass?.close()
        pass = null
    }
}
