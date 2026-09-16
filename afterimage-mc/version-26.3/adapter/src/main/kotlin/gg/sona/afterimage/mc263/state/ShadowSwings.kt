package gg.sona.afterimage.mc263.state

import gg.sona.afterimage.protocol.LocalHand
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.SwingAnimationType
import net.minecraft.world.item.component.SwingAnimation

class ShadowSwing(val nanos: Long, val hand: InteractionHand, val animation: SwingAnimation)

class ShadowSwings {
    private val swings = ArrayDeque<ShadowSwing>()

    val isEmpty: Boolean get() = swings.isEmpty()

    val all: List<ShadowSwing> get() = swings

    val lastNanos: Long get() = swings.lastOrNull()?.nanos ?: Long.MIN_VALUE

    fun push(nanos: Long, hand: InteractionHand, animation: SwingAnimation) {
        if (swings.size >= CAPACITY) swings.removeFirst()
        swings.addLast(ShadowSwing(nanos, hand, animation))
    }

    fun push(nanos: Long, packet: LocalHand) = push(nanos, handOf(packet), animationOf(packet))

    fun copyFrom(other: ShadowSwings) {
        swings.clear()
        swings.addAll(other.swings)
    }

    fun clear() = swings.clear()

    companion object {
        private const val CAPACITY = 16

        fun handOf(packet: LocalHand): InteractionHand = if (packet.hand == LocalHand.OFF_HAND) InteractionHand.OFF_HAND else InteractionHand.MAIN_HAND

        fun animationOf(packet: LocalHand): SwingAnimation {
            val type = SwingAnimationType.values().firstOrNull { it.id == packet.animation } ?: SwingAnimation.DEFAULT.type()
            return SwingAnimation(type, packet.duration)
        }
    }
}
