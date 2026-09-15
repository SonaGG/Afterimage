package gg.sona.afterimage.mc26.replay

import gg.sona.afterimage.mc26.mixin.InterpolationHandlerAccessor
import gg.sona.afterimage.replay.session.ReplaySession
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.world.entity.LivingEntity

class RecorderSync26 {
    fun afterTick(replay: ReplaySession, level: ClientLevel) {
        val local = replay.shadow26.localPlayer
        if (!local.hasPosition || local.entityId < 0) return
        val entity = level.getEntity(local.entityId) as? LivingEntity ?: return
        val interpolation = entity.interpolation as? InterpolationHandlerAccessor
        if (interpolation != null && interpolation.afterimage_interpolationSteps() != 1) interpolation.afterimage_setInterpolationSteps(1)
        if (!entity.noPhysics) entity.noPhysics = true
        entity.yHeadRot = local.yaw
        entity.setOnGround(local.onGround)
    }
}
