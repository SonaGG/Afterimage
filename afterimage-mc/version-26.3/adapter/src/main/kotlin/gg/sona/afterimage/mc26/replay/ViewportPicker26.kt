package gg.sona.afterimage.mc26.replay

import gg.sona.afterimage.editor.host.ViewportPick
import gg.sona.afterimage.mc.common.ViewportProjection
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.state.level.CameraRenderState
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.player.Player
import org.joml.Vector3d

class ViewportPicker26(private val minecraft: Minecraft) {
    var recorderEntityId: () -> Int = { Int.MIN_VALUE }
    var recorderName: () -> String? = { null }
    var hidden: (Entity) -> Boolean = { false }
    val projection = ViewportProjection()

    fun onCameraExtracted(state: CameraRenderState) {
        if (!state.initialized) return
        projection.set(state.projectionMatrix, state.viewRotationMatrix, state.pos.x, state.pos.y, state.pos.z)
    }

    fun tanHalfFov(): FloatArray = projection.tanHalfFov()

    fun ray(normalizedX: Float, normalizedY: Float): Vector3d? = projection.ray(normalizedX, normalizedY)

    fun worldRay(normalizedX: Float, normalizedY: Float): Pair<Vector3d, Vector3d>? = projection.worldRay(normalizedX, normalizedY)

    fun project(x: Double, y: Double, z: Double): FloatArray? = projection.project(x, y, z)

    fun pick(normalizedX: Float, normalizedY: Float): ViewportPick? {
        val level = minecraft.level ?: return null
        val (near, direction) = projection.localRay(normalizedX, normalizedY) ?: return null
        val self = minecraft.player
        val camera = minecraft.cameraEntity
        val originX = projection.originX
        val originY = projection.originY
        val originZ = projection.originZ
        var best: Entity? = null
        var bestDistance = ViewportProjection.MAX_DISTANCE
        for (entity in level.entitiesForRendering()) {
            if (entity === self || entity === camera || !entity.isAlive || hidden(entity)) continue
            val dx = entity.x - originX
            val dy = entity.y - originY
            val dz = entity.z - originZ
            if (dx * dx + dy * dy + dz * dz < MIN_DISTANCE_SQUARED) continue
            val margin = entity.pickRadius.toDouble() + PICK_MARGIN
            val box = entity.boundingBox.inflate(margin, margin, margin)
            val distance = ViewportProjection.intersect(
                near, direction,
                box.minX - originX, box.minY - originY, box.minZ - originZ,
                box.maxX - originX, box.maxY - originY, box.maxZ - originZ,
            )
            if (distance != null && distance < bestDistance) {
                best = entity
                bestDistance = distance
            }
        }
        return describe(best ?: return null)
    }

    private fun describe(entity: Entity): ViewportPick {
        val box = entity.boundingBox
        val bounds = projection.screenBounds(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ)
        val id = entity.id
        val recorder = id == recorderEntityId()
        val name = if (recorder) recorderName() ?: entity.name.string else entity.name.string
        return ViewportPick(
            id,
            name,
            entity is Player,
            recorder,
            entity.uuid.toString(),
            entity.x,
            entity.y,
            entity.z,
            bounds[0],
            bounds[1],
            bounds[2],
            bounds[3],
            (box.maxX - box.minX) / 2.0,
            box.maxY - box.minY,
        )
    }

    private companion object {
        const val PICK_MARGIN = 0.15
        const val MIN_DISTANCE_SQUARED = 0.36
    }
}
