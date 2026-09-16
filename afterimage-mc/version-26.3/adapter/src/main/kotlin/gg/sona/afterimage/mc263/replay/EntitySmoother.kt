package gg.sona.afterimage.mc263.replay

import gg.sona.afterimage.camera.CameraSettings
import gg.sona.afterimage.core.time.Nanos
import gg.sona.afterimage.mc263.state.ShadowEntity
import gg.sona.afterimage.replay.session.ReplaySession
import gg.sona.afterimage.world.EntityKind
import gg.sona.afterimage.world.Pose
import gg.sona.afterimage.world.interpolation.LinearInterpolation
import net.minecraft.client.Minecraft
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.vehicle.boat.AbstractBoat
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart

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
        entity.yRot = pose.yaw
        entity.yRotO = pose.yaw
        entity.xRot = pose.pitch
        entity.xRotO = pose.pitch
        if (entity is LivingEntity) {
            entity.yHeadRot = pose.headYaw
            entity.yHeadRotO = pose.headYaw
        }
    }

    private fun rotationPose(replay: ReplaySession, entity: Entity): Pose? {
        val id = entity.id
        val shadow = replay.shadow
        val local = shadow.localPlayer
        if (id == local.entityId) {
            if (local.history.positions.size < 2) return null
            return local.poseAt(replay.positionNanos - Nanos.ofMillis(RECORDER_LAG_MILLIS), LinearInterpolation)
        }
        val tracked = shadow.entityMap[id] ?: return null
        if (tracked.dead || tracked.history.positions.size < 2 || !smoothable(tracked)) return null
        return tracked.poseAt(replay.positionNanos - Nanos.ofMillis(settings.entityDelayMillis), LinearInterpolation)
    }

    private fun riderPosition(replay: ReplaySession, rider: Entity, vehicle: Entity): DoubleArray? {
        val base = smoothed(replay, vehicle, vehicleScratch) ?: return null
        val tickDelta = minecraft.deltaTracker.getGameTimeDeltaPartialTick(true)
        scratch[0] = base[0] + interpolated(rider.xo, rider.x, tickDelta) - interpolated(vehicle.xo, vehicle.x, tickDelta)
        scratch[1] = base[1] + interpolated(rider.yo, rider.y, tickDelta) - interpolated(vehicle.yo, vehicle.y, tickDelta)
        scratch[2] = base[2] + interpolated(rider.zo, rider.z, tickDelta) - interpolated(vehicle.zo, vehicle.z, tickDelta)
        scratch[3] = interpolated(rider.yRotO.toDouble(), rider.yRot.toDouble(), tickDelta)
        return scratch
    }

    private fun smoothed(replay: ReplaySession, entity: Entity, into: DoubleArray): DoubleArray? {
        val id = entity.id
        val shadow = replay.shadow
        val local = shadow.localPlayer
        if (id == local.entityId) {
            val feet = local.cameraFrames.blended(replay.positionNanos, true)?.position
            if (feet != null) {
                into[0] = feet[0]
                into[1] = feet[1]
                into[2] = feet[2]
                into[3] = entity.yRot.toDouble()
                return into
            }
            if (local.history.positions.size < 2) return null
            return fill(into, local.poseAt(replay.positionNanos - Nanos.ofMillis(RECORDER_LAG_MILLIS), LinearInterpolation))
        }
        val tracked = shadow.entityMap[id] ?: return null
        if (tracked.dead || tracked.history.positions.size < 2 || !smoothable(tracked)) return null
        return fill(into, tracked.poseAt(replay.positionNanos - Nanos.ofMillis(settings.entityDelayMillis), LinearInterpolation))
    }

    private fun fill(into: DoubleArray, pose: Pose): DoubleArray {
        into[0] = pose.x
        into[1] = pose.y
        into[2] = pose.z
        into[3] = pose.yaw.toDouble()
        return into
    }

    private fun smoothable(entity: ShadowEntity): Boolean {
        if (entity.kind == EntityKind.PLAYER || entity.kind == EntityKind.MOB) return true
        val base = entity.entityType.baseClass
        return AbstractMinecart::class.java.isAssignableFrom(base) || AbstractBoat::class.java.isAssignableFrom(base)
    }

    private fun interpolated(last: Double, current: Double, tickDelta: Float): Double = last + (current - last) * tickDelta

    private companion object {
        const val RECORDER_LAG_MILLIS = 40L
    }
}
