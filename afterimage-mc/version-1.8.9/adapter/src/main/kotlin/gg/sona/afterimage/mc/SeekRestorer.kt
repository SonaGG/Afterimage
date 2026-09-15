package gg.sona.afterimage.mc
import gg.sona.afterimage.mc.common.ReplayClock
import gg.sona.afterimage.mc.common.SoundCaptureBase

import gg.sona.afterimage.core.time.Nanos
import gg.sona.afterimage.protocol47.perspective.TransientPackets
import gg.sona.afterimage.replay.session.ReplaySession
import gg.sona.afterimage.protocol47.shadow.ShadowEntity
import gg.sona.afterimage.protocol47.shadow.SwingState
import net.minecraft.client.Minecraft
import net.minecraft.entity.living.LivingEntity
import net.minecraft.util.math.MathHelper
import org.apache.logging.log4j.LogManager

class SeekRestorer(
    private val minecraft: Minecraft,
    private val sounds: SoundCaptureBase,
    private val clock: ReplayClock,
    private val beginTick: (Long) -> Unit,
) {
    private val logger = LogManager.getLogger("Afterimage")

    fun restore(replay: ReplaySession, mirror: VirtualConnection) {
        val world = minecraft.world ?: return
        val player = minecraft.player ?: return
        val position = replay.positionNanos
        val lastTick = replay.lastTickNanos
        try {
            mirror.syncOverlays(replay.shadow.overlays.snapshot(position), position)
            restoreEntities(replay, position, lastTick)
            rebuildParticles(replay, mirror, position, lastTick, player.x, player.y, player.z)
        } catch (error: Throwable) {
            logger.error("Afterimage could not restore transient state after a seek", error)
        }
    }

    private fun restoreEntities(replay: ReplaySession, position: Long, lastTick: Long) {
        val world = minecraft.world ?: return
        replay.shadow.entities.forEach { id, shadow ->
            val entity = world.getEntity(id) as? LivingEntity ?: return@forEach
            if (!shadow.visibleAt(position)) {
                world.removeEntity(id)
                return@forEach
            }
            val deadAt = if (shadow.dead) shadow.deadAtNanos else Long.MIN_VALUE
            val duration = SwingState.duration(shadow.effects, position)
            restoreLiving(entity, shadow.hurtAtNanos, deadAt, shadow.swingAtNanos, duration, lastTick)
        }
        val local = replay.shadow.localPlayer
        val proxy = world.getEntity(local.entityId) as? LivingEntity ?: return
        if (proxy === minecraft.player) return
        restoreLiving(proxy, local.hurtAtNanos, local.deadAtNanos, local.lastSwingNanos, SwingState.duration(local.effects, position), lastTick)
    }

    private fun restoreLiving(entity: LivingEntity, hurtAt: Long, deadAt: Long, swingAt: Long, swingDuration: Int, lastTick: Long) {
        val hurt = SwingState.ticksSince(hurtAt, lastTick)
        entity.damagedTimer = (HURT_TICKS - hurt).coerceIn(0, HURT_TICKS)
        entity.damagedTime = HURT_TICKS
        if (deadAt != Long.MIN_VALUE) {
            entity.health = 0f
            entity.deathTicks = SwingState.ticksSince(deadAt, lastTick).coerceIn(0, ShadowEntity.DEATH_ANIMATION_TICKS)
        } else if (entity.deathTicks > 0) {
            entity.deathTicks = 0
        }
        applySwing(entity, SwingState.at(swingAt, lastTick, swingDuration))
    }

    private fun rebuildParticles(
        replay: ReplaySession,
        mirror: VirtualConnection,
        position: Long,
        lastTick: Long,
        x: Double,
        y: Double,
        z: Double,
    ) {
        val world = minecraft.world ?: return
        val manager = minecraft.particleManager
        manager.setWorld(world)
        val from = position - WINDOW_NANOS
        val events = replay.packetsBetween(from, position, TransientPackets::isVisualEvent)
        val blockX = MathHelper.floor(x)
        val blockY = MathHelper.floor(y)
        val blockZ = MathHelper.floor(z)
        val wasMuted = sounds.muted
        val wasCapturing = sounds.capturing
        sounds.muted = true
        sounds.capturing = false
        val wasManual = clock.manualTick
        clock.manualTick = true
        try {
            var tick = lastTick - ((lastTick - from) / Nanos.PER_TICK) * Nanos.PER_TICK
            for (event in events) {
                while (tick <= event.timestampNanos && tick <= lastTick) {
                    tickParticles(tick, blockX, blockY, blockZ)
                    tick += Nanos.PER_TICK
                }
                mirror.replayEvent(event)
            }
            while (tick <= lastTick) {
                tickParticles(tick, blockX, blockY, blockZ)
                tick += Nanos.PER_TICK
            }
        } finally {
            clock.manualTick = wasManual
            sounds.muted = wasMuted
            sounds.capturing = wasCapturing
        }
    }

    private fun tickParticles(boundary: Long, x: Int, y: Int, z: Int) {
        val world = minecraft.world ?: return
        beginTick(boundary)
        world.doRandomDisplayTicks(x, y, z)
        minecraft.particleManager.tick()
    }

    companion object {
        private const val HURT_TICKS = 10
        private val WINDOW_NANOS = Nanos.ofSeconds(5)

        fun applySwing(entity: LivingEntity, swing: SwingState) {
            entity.armSwinging = swing.swinging
            entity.armSwingingTicks = swing.ticks
            entity.attackAnimationProgress = swing.progress
            entity.lastAttackAnimationProgress = swing.lastProgress
        }
    }
}
