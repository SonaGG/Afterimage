package gg.sona.afterimage.mc263.replay

import gg.sona.afterimage.core.time.Nanos
import gg.sona.afterimage.mc.common.ReplayClock
import gg.sona.afterimage.mc.common.SoundCaptureBase
import gg.sona.afterimage.mc.common.SwingState
import gg.sona.afterimage.mc263.mixin.ExperienceOrbAccessor
import gg.sona.afterimage.mc263.mixin.ItemEntityAccessor
import gg.sona.afterimage.mc263.protocol.McReplayProtocol
import gg.sona.afterimage.mc263.state.ShadowEffect
import gg.sona.afterimage.mc263.state.ShadowSwings
import gg.sona.afterimage.replay.session.ReplaySession
import net.minecraft.client.Minecraft
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.ExperienceOrb
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.entity.LivingEntity
import org.slf4j.LoggerFactory

class SeekRestorer(
    private val minecraft: Minecraft,
    private val sounds: SoundCaptureBase,
    private val clock: ReplayClock,
    private val beginTick: (Long) -> Unit,
    private val animate: (Long) -> Unit,
) {
    private val logger = LoggerFactory.getLogger("Afterimage")

    private var prepared = false

    var lastPrepareNanos: Long = 0L
        private set

    fun prepare(replay: ReplaySession, mirror: VirtualConnection, settleNanos: Long) {
        if (minecraft.level == null || minecraft.player == null) return
        val started = System.nanoTime()
        try {
            rebuildParticles(replay, mirror, replay.positionNanos, replay.lastTickNanos, PARTICLE_LIFETIME_NANOS - settleNanos)
            prepared = true
            lastPrepareNanos = System.nanoTime() - started
        } catch (error: Throwable) {
            logger.error("Afterimage could not rebuild particles before a seek", error)
        }
    }

    fun restore(replay: ReplaySession, mirror: VirtualConnection) {
        val settled = prepared
        prepared = false
        if (minecraft.player == null || minecraft.level == null) return
        val position = replay.positionNanos
        val lastTick = replay.lastTickNanos
        try {
            restoreEntities(replay, position, lastTick, settled)
            if (!settled) rebuildParticles(replay, mirror, position, lastTick, PARTICLE_LIFETIME_NANOS)
        } catch (error: Throwable) {
            logger.error("Afterimage could not restore transient state after a seek", error)
        }
    }

    private fun restoreEntities(replay: ReplaySession, position: Long, lastTick: Long, settled: Boolean) {
        val level = minecraft.level ?: return
        val shadow = replay.shadow
        shadow.entityMap.forEach { id, tracked ->
            val any = level.getEntity(id) ?: return@forEach
            restoreAge(any, tracked.spawnedAtNanos, lastTick)
            val entity = any as? LivingEntity ?: return@forEach
            if (!tracked.visibleAt(position)) {
                level.removeEntity(id, Entity.RemovalReason.DISCARDED)
                return@forEach
            }
            if (settled) return@forEach
            val deadAt = if (tracked.dead) tracked.deadAtNanos else Long.MIN_VALUE
            restoreLiving(entity, tracked.hurtAtNanos, deadAt, tracked.swings, tracked.effects.values, lastTick)
        }
        val local = shadow.localPlayer
        val proxy = level.getEntity(local.entityId) as? LivingEntity ?: return
        if (proxy === minecraft.player || settled) return
        restoreLiving(proxy, local.hurtAtNanos, local.deadAtNanos, local.swings, local.effects.values, lastTick)
    }


    private fun restoreAge(entity: Entity, spawnedAt: Long, lastTick: Long) {
        if (spawnedAt == Long.MIN_VALUE) return
        val age = SwingState.ticksSince(spawnedAt, lastTick).coerceAtLeast(0)
        entity.tickCount = age
        when (entity) {
            is ItemEntity -> (entity as ItemEntityAccessor).afterimage_setAge(age)
            is ExperienceOrb -> (entity as ExperienceOrbAccessor).afterimage_setAge(age)
            else -> Unit
        }
    }

    private fun restoreLiving(entity: LivingEntity, hurtAt: Long, deadAt: Long, swings: ShadowSwings, effects: Collection<ShadowEffect>, lastTick: Long) {
        val hurt = SwingState.ticksSince(hurtAt, lastTick)
        entity.hurtTime = (HURT_TICKS - hurt).coerceIn(0, HURT_TICKS)
        entity.hurtDuration = HURT_TICKS
        if (deadAt != Long.MIN_VALUE) {
            entity.health = 0f
            entity.deathTime = SwingState.ticksSince(deadAt, lastTick).coerceIn(0, DEATH_ANIMATION_TICKS)
        } else if (entity.deathTime > 0) {
            entity.deathTime = 0
        }
        SwingStates.apply(entity, swings, effects, lastTick)
    }

    private fun rebuildParticles(replay: ReplaySession, mirror: VirtualConnection, position: Long, lastTick: Long, windowNanos: Long) {
        val level = minecraft.level ?: return
        val engine = minecraft.particleEngine
        engine.setLevel(level)
        val from = position - windowNanos
        val events = replay.packetsBetween(from, position) { McReplayProtocol.INSTANCE.isVisualEvent(it) }
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
                    tickParticles(tick)
                    tick += Nanos.PER_TICK
                }
                mirror.replayEvent(event)
            }
            while (tick <= lastTick) {
                tickParticles(tick)
                tick += Nanos.PER_TICK
            }
        } finally {
            clock.manualTick = wasManual
            sounds.muted = wasMuted
            sounds.capturing = wasCapturing
        }
    }

    private fun tickParticles(boundary: Long) {
        beginTick(boundary)
        minecraft.level?.tickBlockEntities()
        animate(boundary)
        minecraft.particleEngine.tick()
    }

    private companion object {
        const val HURT_TICKS = 10
        const val DEATH_ANIMATION_TICKS = 20
        val PARTICLE_LIFETIME_NANOS = Nanos.ofSeconds(15) + Nanos.PER_TICK
    }
}
