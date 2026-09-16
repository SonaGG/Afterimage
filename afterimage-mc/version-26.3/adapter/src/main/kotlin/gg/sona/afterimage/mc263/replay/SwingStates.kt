package gg.sona.afterimage.mc263.replay

import gg.sona.afterimage.mc.common.SwingState
import gg.sona.afterimage.mc263.mixin.LivingEntityAccessor
import gg.sona.afterimage.mc263.mixin.SwingStateAccessor
import gg.sona.afterimage.mc263.state.ShadowEffect
import gg.sona.afterimage.mc263.state.ShadowSwings
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.item.component.SwingAnimation
import kotlin.math.min

object SwingStates {
    private class Simulation {
        var current: LivingEntity.SwingDescription? = null
        var ticks = 0
        var animation = 0f
        var oldAnimation = 0f

        fun startIfAble(description: LivingEntity.SwingDescription) {
            val active = current
            if (active != null && ticks <= active.durationTicks() / 2 && ticks > 0) return
            if (description != active) {
                oldAnimation = 0f
                animation = 0f
            }
            current = description
            ticks = 0
        }

        fun tick() {
            oldAnimation = animation
            val active = current
            if (active == null) {
                animation = 0f
                return
            }
            if (active.durationTicks() > 0) animation = min(ticks.toFloat() / active.durationTicks(), 1f)
            if (ticks++ > active.durationTicks()) {
                current = null
                animation = 0f
            }
        }

        fun tick(count: Int) {
            repeat(min(count, MAX_SIMULATED_TICKS)) { tick() }
        }
    }

    fun duration(effects: Collection<ShadowEffect>, nanos: Long, animation: SwingAnimation = SwingAnimation.DEFAULT): Int {
        val haste = effects.firstOrNull { it.effect == MobEffects.HASTE && it.remainingTicks(nanos) > 0 }?.amplifier
        val fatigue = effects.firstOrNull { it.effect == MobEffects.MINING_FATIGUE && it.remainingTicks(nanos) > 0 }?.amplifier
        return SwingState.duration(animation.duration(), haste, fatigue)
    }

    fun apply(entity: LivingEntity, swings: ShadowSwings, effects: Collection<ShadowEffect>, lastTick: Long) {
        val simulation = Simulation()
        val history = swings.all
        for ((index, swing) in history.withIndex()) {
            val description = LivingEntity.SwingDescription(swing.hand, swing.animation, duration(effects, swing.nanos, swing.animation))
            simulation.startIfAble(description)
            val next = history.getOrNull(index + 1)
            val remaining = SwingState.ticksSince(swing.nanos, lastTick)
            simulation.tick(if (next == null) remaining else remaining - SwingState.ticksSince(next.nanos, lastTick))
        }
        val state = (entity as LivingEntityAccessor).afterimage_swingState() as SwingStateAccessor
        state.afterimage_setCurrentSwing(simulation.current)
        state.afterimage_setTicks(simulation.ticks)
        state.afterimage_setAnimation(simulation.animation)
        state.afterimage_setOldAnimation(simulation.oldAnimation)
    }

    fun reset(entity: LivingEntity) {
        val state = (entity as LivingEntityAccessor).afterimage_swingState() as SwingStateAccessor
        state.afterimage_setCurrentSwing(null)
        state.afterimage_setTicks(0)
        state.afterimage_setAnimation(0f)
        state.afterimage_setOldAnimation(0f)
    }

    fun copy(from: LivingEntity, to: LivingEntity) {
        val source = (from as LivingEntityAccessor).afterimage_swingState() as SwingStateAccessor
        val target = (to as LivingEntityAccessor).afterimage_swingState() as SwingStateAccessor
        target.afterimage_setCurrentSwing(source.afterimage_currentSwing())
        target.afterimage_setTicks(source.afterimage_ticks())
        target.afterimage_setAnimation(source.afterimage_animation())
        target.afterimage_setOldAnimation(source.afterimage_oldAnimation())
    }

    private const val MAX_SIMULATED_TICKS = 64
}
