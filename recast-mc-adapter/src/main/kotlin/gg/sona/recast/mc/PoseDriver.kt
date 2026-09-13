package gg.sona.recast.mc

import gg.sona.recast.editor.EditorSession
import gg.sona.recast.editor.pose.BodyPart
import net.minecraft.client.render.model.ModelPart
import net.minecraft.client.render.model.entity.HumanoidModel
import net.minecraft.entity.Entity
import net.minecraft.entity.living.LivingEntity
import java.util.*

class PoseDriver {

    var active: () -> Boolean = { false }
    var session: () -> EditorSession? = { null }

    var captureEntity: () -> Int? = { null }
    var renderPosition: (Entity) -> DoubleArray? = { null }
    var tickDelta: () -> Float = { 1f }

    private val captured = DoubleArray(HEADER + BodyPart.entries.size * STRIDE)

    private val replaced = WeakHashMap<HumanoidModel, FloatArray>()
    private var capturedId = Int.MIN_VALUE
    private var capturedAtNanos = 0L

    fun apply(model: HumanoidModel, entity: Entity) {
        if (!active()) return
        val editor = session() ?: return
        val replay = editor.replay ?: return
        val project = editor.project
        if (project.poses.isNotEmpty()) {
            val pose = project.poseAt(entity.networkId, replay.positionNanos)
            if (pose != null) {
                remember(model)
                for ((part, target) in pose.parts) {
                    val weight = target.weight.coerceIn(0.0, 1.0)
                    if (weight <= 0.0) continue
                    val piece = piece(model, part)
                    piece.rotationX = blend(piece.rotationX, Math.toRadians(target.x), weight)
                    piece.rotationY = blend(piece.rotationY, Math.toRadians(target.y), weight)
                    piece.rotationZ = blend(piece.rotationZ, Math.toRadians(target.z), weight)
                }
                model.hat.rotationX = model.head.rotationX
                model.hat.rotationY = model.head.rotationY
                model.hat.rotationZ = model.head.rotationZ
            }
        }
        if (entity.networkId == captureEntity()) capture(model, entity)
    }

    fun restore(model: HumanoidModel) {
        val saved = replaced.remove(model) ?: return
        for (part in BodyPart.entries) {
            val piece = piece(model, part)
            val base = part.ordinal * 3
            piece.rotationX = saved[base]
            piece.rotationY = saved[base + 1]
            piece.rotationZ = saved[base + 2]
        }
        model.hat.rotationX = model.head.rotationX
        model.hat.rotationY = model.head.rotationY
        model.hat.rotationZ = model.head.rotationZ
    }

    private fun remember(model: HumanoidModel) {
        val saved = replaced.getOrPut(model) { FloatArray(BodyPart.entries.size * 3) }
        for (part in BodyPart.entries) {
            val piece = piece(model, part)
            val base = part.ordinal * 3
            saved[base] = piece.rotationX
            saved[base + 1] = piece.rotationY
            saved[base + 2] = piece.rotationZ
        }
    }

    fun captured(entityId: Int): DoubleArray? {
        if (entityId != capturedId || System.nanoTime() - capturedAtNanos > CAPTURE_TTL_NANOS) return null
        return captured.copyOf()
    }

    private fun capture(model: HumanoidModel, entity: Entity) {
        val delta = tickDelta()
        val position = renderPosition(entity)
        captured[0] = position?.get(0) ?: (entity.lastX + (entity.x - entity.lastX) * delta)
        captured[1] = position?.get(1) ?: (entity.lastY + (entity.y - entity.lastY) * delta)
        captured[2] = position?.get(2) ?: (entity.lastZ + (entity.z - entity.lastZ) * delta)
        captured[3] =
            if (entity is LivingEntity) rotated(entity.lastBodyYaw, entity.bodyYaw, delta) else entity.yaw.toDouble()
        captured[4] = if (model.sneaking) 1.0 else 0.0
        for (part in BodyPart.entries) {
            val piece = piece(model, part)
            val base = HEADER + part.ordinal * STRIDE
            captured[base] = piece.x.toDouble()
            captured[base + 1] = piece.y.toDouble()
            captured[base + 2] = piece.z.toDouble()
            captured[base + 3] = piece.rotationX.toDouble()
            captured[base + 4] = piece.rotationY.toDouble()
            captured[base + 5] = piece.rotationZ.toDouble()
        }
        capturedId = entity.networkId
        capturedAtNanos = System.nanoTime()
    }

    private fun rotated(last: Float, current: Float, delta: Float): Double {
        var step = current - last
        while (step < -180f) step += 360f
        while (step >= 180f) step -= 360f
        return (last + delta * step).toDouble()
    }

    private fun piece(model: HumanoidModel, part: BodyPart): ModelPart = when (part) {
        BodyPart.HEAD -> model.head
        BodyPart.BODY -> model.body
        BodyPart.RIGHT_ARM -> model.rightArm
        BodyPart.LEFT_ARM -> model.leftArm
        BodyPart.RIGHT_LEG -> model.rightLeg
        BodyPart.LEFT_LEG -> model.leftLeg
    }

    private fun blend(current: Float, target: Double, weight: Double): Float {
        var delta = (target - current) % (Math.PI * 2.0)
        if (delta > Math.PI) delta -= Math.PI * 2.0
        if (delta < -Math.PI) delta += Math.PI * 2.0
        return (current + delta * weight).toFloat()
    }

    companion object {
        const val HEADER = 5
        const val STRIDE = 6
        private const val CAPTURE_TTL_NANOS = 1_000_000_000L
    }
}
