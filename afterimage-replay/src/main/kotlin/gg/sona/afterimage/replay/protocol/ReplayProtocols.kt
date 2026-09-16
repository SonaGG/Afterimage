package gg.sona.afterimage.replay.protocol

import java.util.*

object ReplayProtocols {
    private val registered = LinkedHashMap<Int, ReplayProtocol>()

    @Volatile
    private var discovered = false

    fun register(protocol: ReplayProtocol) {
        synchronized(registered) { registered[protocol.version] = protocol }
    }

    fun forVersion(version: Int): ReplayProtocol {
        discover()
        synchronized(registered) {
            return registered[version] ?: throw IllegalStateException("no replay protocol registered for protocol version $version")
        }
    }

    fun all(): List<ReplayProtocol> {
        discover()
        synchronized(registered) { return registered.values.toList() }
    }

    private fun discover() {
        if (discovered) return
        synchronized(registered) {
            if (discovered) return
            discovered = true
            for (protocol in ServiceLoader.load(ReplayProtocol::class.java, ReplayProtocols::class.java.classLoader)) {
                registered.putIfAbsent(protocol.version, protocol)
            }
        }
    }
}
