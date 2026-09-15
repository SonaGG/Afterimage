package gg.sona.afterimage.mc26.replay

import gg.sona.afterimage.mc.common.SwingState
import gg.sona.afterimage.mc26.mixin.LivingEntityAccessor
import gg.sona.afterimage.mc26.mixin.SwingStateAccessor
import gg.sona.afterimage.mc26.state.ShadowEffect26
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.item.component.SwingAnimation

object SwingState26 {
    fun at(swingNanos: Long, tickNanos: Long, duration: Int): SwingState = SwingState.at(swingNanos, tickNanos, duration)

    fun duration(effects: Collection<ShadowEffect26>, nanos: Long): Int {
        val haste = effects.firstOrNull { it.effect == MobEffects.HASTE && it.remainingTicks(nanos) > 0 }?.amplifier
        val fatigue = effects.firstOrNull { it.effect == MobEffects.MINING_FATIGUE && it.remainingTicks(nanos) > 0 }?.amplifier
        return SwingState.duration(SwingAnimation.DEFAULT.duration(), haste, fatigue)
    }

    fun apply(entity: LivingEntity, swing: SwingState, duration: Int) {
        val state = (entity as LivingEntityAccessor).afterimage_swingState() as SwingStateAccessor
        if (!swing.swinging && swing.lastProgress == 0f) {
            state.afterimage_setCurrentSwing(null)
            state.afterimage_setTicks(0)
            state.afterimage_setAnimation(0f)
            state.afterimage_setOldAnimation(0f)
            return
        }
        state.afterimage_setCurrentSwing(LivingEntity.SwingDescription(entity.usedItemHand, SwingAnimation.DEFAULT, duration))
        state.afterimage_setTicks((swing.ticks + 1).coerceAtLeast(0))
        state.afterimage_setAnimation(swing.progress)
        state.afterimage_setOldAnimation(swing.lastProgress)
    }

    fun copy(from: LivingEntity, to: LivingEntity) {
        val source = (from as LivingEntityAccessor).afterimage_swingState() as SwingStateAccessor
        val target = (to as LivingEntityAccessor).afterimage_swingState() as SwingStateAccessor
        target.afterimage_setCurrentSwing(source.afterimage_currentSwing())
        target.afterimage_setTicks(source.afterimage_ticks())
        target.afterimage_setAnimation(source.afterimage_animation())
        target.afterimage_setOldAnimation(source.afterimage_oldAnimation())
    }
}
