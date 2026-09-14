package gg.sona.afterimage.mc

import gg.sona.afterimage.camera.CameraSettings
import gg.sona.afterimage.core.time.Nanos
import gg.sona.afterimage.mc.mixin.MinecraftAccessor
import gg.sona.afterimage.replay.session.ReplaySession
import gg.sona.afterimage.replay.state.interpolation.LinearInterpolation
import gg.sona.afterimage.replay.state.shadow.EntityKind
import gg.sona.afterimage.replay.state.shadow.Pose
import net.minecraft.client.Minecraft
import net.minecraft.entity.Entity
import net.minecraft.entity.living.LivingEntity

class EntitySmoother(private val minecraft: Minecraft, private val settings: CameraSettings) {
    private val scratch = DoubleArray(4)
    private val vehicleScratch = DoubleArray(4)

    fun renderPosition(replay: ReplaySession?, entity: Entity): DoubleArray? {
        if (replay == null || !settings.smoothEntities) return null
        if (entity === minecraft.player) return null
        val vehicle = entity.vehicle
        if (vehicle != null) return riderPosition(replay, entity, vehicle)
        return smoothed(replay, entity, scratch)
    }

    fun applyRotation(replay: ReplaySession?, entity: Entity) {
        if (replay == null || !settings.smoothEntities || !settings.smoothEntityRotation) return
        if (entity === minecraft.player) return
        val pose = rotationPose(replay, entity) ?: return
        entity.yaw = pose.yaw
        entity.lastYaw = pose.yaw
        entity.pitch = pose.pitch
        entity.lastPitch = pose.pitch
        if (entity is LivingEntity) {
            entity.headYaw = pose.headYaw
            entity.lastHeadYaw = pose.headYaw
        }
    }

    private fun rotationPose(replay: ReplaySession, entity: Entity): Pose? {
        val id = entity.networkId
        val local = replay.shadow.localPlayer
        if (id == local.entityId) {
            if (local.history.positions.size < 2) return null
            return local.poseAt(replay.positionNanos - Nanos.ofMillis(RECORDER_LAG_MILLIS), LinearInterpolation)
        }
        val shadow = replay.shadow.entities[id] ?: return null
        if (shadow.dead || shadow.history.positions.size < 2 || !smoothable(shadow.kind, shadow.type)) return null
        return shadow.poseAt(replay.positionNanos - Nanos.ofMillis(settings.entityDelayMillis), LinearInterpolation)
    }

    private fun riderPosition(replay: ReplaySession, rider: Entity, vehicle: Entity): DoubleArray? {
        val base = smoothed(replay, vehicle, vehicleScratch) ?: return null
        val tickDelta = (minecraft as MinecraftAccessor).`afterimage$timer`().partialTick
        scratch[0] =
            base[0] + interpolated(rider.lastX, rider.x, tickDelta) - interpolated(vehicle.lastX, vehicle.x, tickDelta)
        scratch[1] =
            base[1] + interpolated(rider.lastY, rider.y, tickDelta) - interpolated(vehicle.lastY, vehicle.y, tickDelta)
        scratch[2] =
            base[2] + interpolated(rider.lastZ, rider.z, tickDelta) - interpolated(vehicle.lastZ, vehicle.z, tickDelta)
        scratch[3] = interpolated(rider.lastYaw.toDouble(), rider.yaw.toDouble(), tickDelta)
        return scratch
    }

    private fun smoothed(replay: ReplaySession, entity: Entity, into: DoubleArray): DoubleArray? {
        val id = entity.networkId
        val local = replay.shadow.localPlayer
        if (id == local.entityId) {
            val feet = local.cameraFrames.blended(replay.positionNanos, true)?.position
            if (feet != null) {
                into[0] = feet[0]
                into[1] = feet[1]
                into[2] = feet[2]
                into[3] = entity.yaw.toDouble()
                return into
            }
            if (local.history.positions.size < 2) return null
            val pose = local.poseAt(replay.positionNanos - Nanos.ofMillis(RECORDER_LAG_MILLIS), LinearInterpolation)
            return fill(into, pose)
        }
        val shadow = replay.shadow.entities[id] ?: return null
        if (shadow.dead || shadow.history.positions.size < 2 || !smoothable(shadow.kind, shadow.type)) return null
        return fill(
            into,
            shadow.poseAt(replay.positionNanos - Nanos.ofMillis(settings.entityDelayMillis), LinearInterpolation)
        )
    }

    private fun fill(into: DoubleArray, pose: Pose): DoubleArray {
        into[0] = pose.x
        into[1] = pose.y
        into[2] = pose.z
        into[3] = pose.yaw.toDouble()
        return into
    }

    private fun smoothable(kind: EntityKind, type: Int): Boolean =
        kind == EntityKind.PLAYER || kind == EntityKind.MOB || (kind == EntityKind.OBJECT && type in VEHICLE_TYPES)

    private fun interpolated(last: Double, current: Double, tickDelta: Float): Double =
        last + (current - last) * tickDelta

    private companion object {
        const val RECORDER_LAG_MILLIS = 40L
        val VEHICLE_TYPES = setOf(1, 10)
    }
}
