package gg.sona.afterimage.core.concurrent

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReferenceArray

class MpscRingBuffer<T : Any>(requestedCapacity: Int) {
    val capacity: Int = ceilingPowerOfTwo(requestedCapacity)

    private val mask = capacity - 1
    private val slots = AtomicReferenceArray<T?>(capacity)
    private val producerIndex = AtomicLong(0)
    private val consumerIndex = AtomicLong(0)

    @Volatile
    private var consumerIndexCache = 0L

    fun offer(element: T): Boolean {
        var tail: Long
        while (true) {
            tail = producerIndex.get()
            val wrapPoint = tail - capacity
            if (consumerIndexCache <= wrapPoint) {
                val head = consumerIndex.get()
                if (head <= wrapPoint) return false
                consumerIndexCache = head
            }
            if (producerIndex.compareAndSet(tail, tail + 1)) break
        }
        slots.lazySet((tail and mask.toLong()).toInt(), element)
        return true
    }

    fun poll(): T? {
        val head = consumerIndex.get()
        val slot = (head and mask.toLong()).toInt()
        var element = slots.get(slot)
        if (element == null) {
            if (head == producerIndex.get()) return null
            do {
                Thread.onSpinWait()
                element = slots.get(slot)
            } while (element == null)
        }
        slots.lazySet(slot, null)
        consumerIndex.lazySet(head + 1)
        return element
    }

    fun isEmpty(): Boolean = consumerIndex.get() == producerIndex.get()

    fun size(): Int {
        while (true) {
            val before = consumerIndex.get()
            val tail = producerIndex.get()
            val after = consumerIndex.get()
            if (before == after) return (tail - after).toInt()
        }
    }

    private companion object {
        fun ceilingPowerOfTwo(value: Int): Int {
            require(value in 2..(1 shl 30)) { "capacity must be between 2 and 2^30" }
            return Integer.highestOneBit(value - 1) shl 1
        }
    }
}
