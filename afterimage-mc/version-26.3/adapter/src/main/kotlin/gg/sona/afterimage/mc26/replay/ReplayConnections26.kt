package gg.sona.afterimage.mc26.replay

import net.minecraft.network.Connection
import java.util.*

object ReplayConnections26 {
    private val connections: MutableSet<Connection> = Collections.newSetFromMap(WeakHashMap())

    @JvmStatic
    fun register(connection: Connection) {
        synchronized(connections) { connections.add(connection) }
    }

    @JvmStatic
    fun unregister(connection: Connection) {
        synchronized(connections) { connections.remove(connection) }
    }

    @JvmStatic
    fun isReplay(connection: Connection?): Boolean {
        if (connection == null) return false
        synchronized(connections) { return connection in connections }
    }
}
