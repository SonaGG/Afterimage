package gg.sona.afterimage.mc189.render

import gg.sona.afterimage.mc189.McGfx
import gg.sona.afterimage.mc189.replay.CameraDriver
import gg.sona.afterimage.mc189.replay.ReplayCameraEntity
import gg.sona.afterimage.camera.CameraPose
import gg.sona.afterimage.gfx.Filter
import gg.sona.afterimage.mc189.McGfx.makeOpaque
import gg.sona.afterimage.mc189.McGfx.setFilter
import net.minecraft.client.Minecraft
import net.minecraft.client.render.pipeline.RenderTarget
import org.apache.logging.log4j.LogManager
import org.lwjgl.opengl.GL11

class PreviewRenderer(private val minecraft: Minecraft, private val camera: CameraDriver) {
    private val logger = LogManager.getLogger("Afterimage")
    private var target: RenderTarget? = null
    private var requestedPose: CameraPose? = null
    private var requestedWidth = 0
    private var requestedHeight = 0
    private var requestFrame = 0L
    private var frame = 0L
    private var entity: ReplayCameraEntity? = null
    var rendering = false
        private set
    var textureId = 0
        private set
    var width = 0
        private set
    var height = 0
        private set

    fun request(pose: CameraPose?, width: Int, height: Int) {
        requestedPose = pose
        requestedWidth = width.coerceIn(16, 2048)
        requestedHeight = height.coerceIn(16, 2048)
        requestFrame = frame
    }

    fun onWorldRendered(tickDelta: Float, finishNanos: Long) {
        frame++
        if (rendering) return
        val pose = requestedPose ?: return releaseIfIdle()
        if (frame - requestFrame > 2) return releaseIfIdle()
        val world = minecraft.world ?: return
        if (camera.exportPose != null) return
        val current = target?.takeIf { it.width == requestedWidth && it.height == requestedHeight } ?: recreate()
        val previousCamera = minecraft.camera
        val previousWidth = minecraft.width
        val previousHeight = minecraft.height
        val previewEntity = entity?.takeIf { it.world === world } ?: ReplayCameraEntity(world).also { entity = it }
        previewEntity.place(pose)
        rendering = true
        GL11.glMatrixMode(GL11.GL_PROJECTION)
        GL11.glPushMatrix()
        GL11.glMatrixMode(GL11.GL_MODELVIEW)
        GL11.glPushMatrix()
        try {
            camera.previewPose = pose
            minecraft.setCamera(previewEntity)
            minecraft.width = current.width
            minecraft.height = current.height
            current.bindWrite(true)
            minecraft.gameRenderer.renderWorld(tickDelta, finishNanos)
            current.makeOpaque()
            textureId = current.colorTextureId
            width = current.width
            height = current.height
        } catch (error: Throwable) {
            logger.warn("Afterimage preview render failed", error)
            textureId = 0
        } finally {
            rendering = false
            camera.previewPose = null
            minecraft.setCamera(previousCamera)
            minecraft.width = previousWidth
            minecraft.height = previousHeight
            minecraft.renderTarget.bindWrite(true)
            minecraft.worldRenderer.onViewChanged()
            GL11.glMatrixMode(GL11.GL_PROJECTION)
            GL11.glPopMatrix()
            GL11.glMatrixMode(GL11.GL_MODELVIEW)
            GL11.glPopMatrix()
        }
    }

    private fun releaseIfIdle() {
        if (target != null && frame - requestFrame > 600) release()
    }

    private fun recreate(): RenderTarget {
        target?.destroyBuffers()
        val created = RenderTarget(requestedWidth, requestedHeight, true)
        created.setClearColor(0f, 0f, 0f, 1f)
        created.setFilter(Filter.LINEAR)
        target = created
        return created
    }

    fun release() {
        target?.destroyBuffers()
        target = null
        textureId = 0
        width = 0
        height = 0
    }
}
