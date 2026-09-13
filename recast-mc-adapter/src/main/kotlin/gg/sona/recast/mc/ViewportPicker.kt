package gg.sona.recast.mc

import gg.sona.recast.editor.host.ViewportPick
import net.minecraft.client.Minecraft
import net.minecraft.entity.Entity
import net.minecraft.entity.living.player.PlayerEntity
import org.joml.Matrix4f
import org.joml.Vector3d
import org.joml.Vector4f
import org.lwjgl.BufferUtils
import org.lwjgl.opengl.GL11

class ViewportPicker(private val minecraft: Minecraft) {

    var recorderEntityId: () -> Int = { Int.MIN_VALUE }
    var recorderName: () -> String? = { null }
    var hidden: (Entity) -> Boolean = { false }

    private val buffer = BufferUtils.createFloatBuffer(16)
    private val projection = Matrix4f()
    private val modelView = Matrix4f()
    private val viewProjection = Matrix4f()
    private val inverse = Matrix4f()
    private var originX = 0.0
    private var originY = 0.0
    private var originZ = 0.0
    private var valid = false

    fun onCameraSetup(tickDelta: Float) {
        val view = minecraft.camera ?: return
        buffer.clear()
        GL11.glGetFloatv(GL11.GL_PROJECTION_MATRIX, buffer)
        projection.set(buffer)
        buffer.clear()
        GL11.glGetFloatv(GL11.GL_MODELVIEW_MATRIX, buffer)
        modelView.set(buffer)
        projection.mul(modelView, viewProjection)
        viewProjection.invert(inverse)
        originX = view.lastX + (view.x - view.lastX) * tickDelta
        originY = view.lastY + (view.y - view.lastY) * tickDelta
        originZ = view.lastZ + (view.z - view.lastZ) * tickDelta
        valid = true
    }

    fun ray(normalizedX: Float, normalizedY: Float): Vector3d? = worldRay(normalizedX, normalizedY)?.second

    fun worldRay(normalizedX: Float, normalizedY: Float): Pair<Vector3d, Vector3d>? {
        if (!valid) return null
        val ndcX = normalizedX * 2f - 1f
        val ndcY = 1f - normalizedY * 2f
        val near = unproject(ndcX, ndcY, -1f) ?: return null
        val far = unproject(ndcX, ndcY, 1f) ?: return null
        val direction = Vector3d(far).sub(near)
        if (direction.lengthSquared() < 1e-9) return null
        return Vector3d(near).add(originX, originY, originZ) to direction.normalize()
    }

    fun project(x: Double, y: Double, z: Double): FloatArray? {
        if (!valid) return null
        val point = Vector4f((x - originX).toFloat(), (y - originY).toFloat(), (z - originZ).toFloat(), 1f)
        viewProjection.transform(point)
        if (point.w <= 1e-6f) return null
        return floatArrayOf((point.x / point.w + 1f) / 2f, (1f - point.y / point.w) / 2f, point.z / point.w)
    }

    fun pick(normalizedX: Float, normalizedY: Float): ViewportPick? {
        if (!valid) return null
        val world = minecraft.world ?: return null
        val ndcX = normalizedX * 2f - 1f
        val ndcY = 1f - normalizedY * 2f
        val near = unproject(ndcX, ndcY, -1f) ?: return null
        val far = unproject(ndcX, ndcY, 1f) ?: return null
        val direction = Vector3d(far).sub(near)
        if (direction.lengthSquared() < 1e-9) return null
        direction.normalize()
        val self = minecraft.player
        var best: Entity? = null
        var bestDistance = MAX_DISTANCE
        for (entity in world.entities) {
            if (entity === self || entity === minecraft.camera || !entity.isAlive || hidden(entity)) continue
            val dx = entity.x - originX
            val dy = entity.y - originY
            val dz = entity.z - originZ
            if (dx * dx + dy * dy + dz * dz < MIN_DISTANCE_SQUARED) continue
            val box = entity.shape.grown(
                entity.pickRadius.toDouble() + PICK_MARGIN,
                entity.pickRadius.toDouble() + PICK_MARGIN,
                entity.pickRadius.toDouble() + PICK_MARGIN
            )
            val distance = intersect(
                near,
                direction,
                box.minX - originX,
                box.minY - originY,
                box.minZ - originZ,
                box.maxX - originX,
                box.maxY - originY,
                box.maxZ - originZ
            )
            if (distance != null && distance < bestDistance) {
                best = entity
                bestDistance = distance
            }
        }
        val hit = best ?: return null
        return describe(hit)
    }

    private fun describe(entity: Entity): ViewportPick {
        val box = entity.shape
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        val corner = Vector4f()
        for (index in 0 until 8) {
            val x = (if (index and 1 == 0) box.minX else box.maxX) - originX
            val y = (if (index and 2 == 0) box.minY else box.maxY) - originY
            val z = (if (index and 4 == 0) box.minZ else box.maxZ) - originZ
            corner.set(x.toFloat(), y.toFloat(), z.toFloat(), 1f)
            viewProjection.transform(corner)
            if (corner.w <= 1e-6f) continue
            val sx = (corner.x / corner.w + 1f) / 2f
            val sy = (1f - corner.y / corner.w) / 2f
            if (sx < minX) minX = sx
            if (sy < minY) minY = sy
            if (sx > maxX) maxX = sx
            if (sy > maxY) maxY = sy
        }
        val id = entity.networkId
        val recorder = id == recorderEntityId()
        val name = when {
            recorder -> recorderName() ?: entity.name
            entity is PlayerEntity -> entity.name
            else -> entity.name
        }
        return ViewportPick(
            id,
            name,
            entity is PlayerEntity,
            recorder,
            entity.uuid?.toString(),
            entity.x,
            entity.y,
            entity.z,
            minX,
            minY,
            maxX,
            maxY,
            (box.maxX - box.minX) / 2.0,
            box.maxY - box.minY
        )
    }

    private fun unproject(x: Float, y: Float, z: Float): Vector3d? {
        val point = Vector4f(x, y, z, 1f)
        inverse.transform(point)
        if (Math.abs(point.w) < 1e-9f) return null
        return Vector3d((point.x / point.w).toDouble(), (point.y / point.w).toDouble(), (point.z / point.w).toDouble())
    }

    private fun intersect(
        origin: Vector3d,
        direction: Vector3d,
        minX: Double,
        minY: Double,
        minZ: Double,
        maxX: Double,
        maxY: Double,
        maxZ: Double
    ): Double? {
        var tMin = 0.0
        var tMax = MAX_DISTANCE
        val origins = doubleArrayOf(origin.x, origin.y, origin.z)
        val directions = doubleArrayOf(direction.x, direction.y, direction.z)
        val mins = doubleArrayOf(minX, minY, minZ)
        val maxs = doubleArrayOf(maxX, maxY, maxZ)
        for (axis in 0 until 3) {
            val d = directions[axis]
            if (Math.abs(d) < 1e-9) {
                if (origins[axis] < mins[axis] || origins[axis] > maxs[axis]) return null
                continue
            }
            var t1 = (mins[axis] - origins[axis]) / d
            var t2 = (maxs[axis] - origins[axis]) / d
            if (t1 > t2) {
                val swap = t1
                t1 = t2
                t2 = swap
            }
            if (t1 > tMin) tMin = t1
            if (t2 < tMax) tMax = t2
            if (tMin > tMax) return null
        }
        return tMin
    }

    private companion object {
        const val MAX_DISTANCE = 96.0
        const val PICK_MARGIN = 0.15
        const val MIN_DISTANCE_SQUARED = 0.36
    }
}
