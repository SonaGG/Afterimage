package gg.sona.afterimage.core.event

import java.util.concurrent.CopyOnWriteArrayList

class Listeners<L : Any> : Iterable<L> {
    private val registered = CopyOnWriteArrayList<L>()

    val isEmpty: Boolean get() = registered.isEmpty()

    fun add(listener: L): AutoCloseable {
        registered.addIfAbsent(listener)
        return AutoCloseable { registered.remove(listener) }
    }

    fun remove(listener: L) {
        registered.remove(listener)
    }

    fun clear() {
        registered.clear()
    }

    override fun iterator(): Iterator<L> = registered.iterator()

    inline fun dispatch(action: (L) -> Unit) {
        for (listener in this) action(listener)
    }
}
