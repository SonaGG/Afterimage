package gg.sona.recast.loader

import gg.sona.recast.mc.RecastHooks
import gg.sona.recast.mc.RecastRuntime
import net.fabricmc.api.ClientModInitializer
import net.minecraft.client.Minecraft
import net.ornithemc.osl.lifecycle.api.client.MinecraftClientEvents
import net.ornithemc.osl.networking.api.client.ClientConnectionEvents
import org.apache.logging.log4j.LogManager

class RecastMod : ClientModInitializer {

    override fun onInitializeClient() {
        MinecraftClientEvents.READY.register { minecraft -> start(minecraft) }
        MinecraftClientEvents.TICK_END.register { isolate("client tick") { runtime?.onClientTick() } }
        MinecraftClientEvents.STOP.register { isolate("shutdown") { stop() } }
        ClientConnectionEvents.DISCONNECT.register { isolate("disconnect") { runtime?.onDisconnect() } }
    }

    private fun start(minecraft: Minecraft) {
        if (runtime != null) return
        val created = RecastRuntime(minecraft)
        runtime = created
        RecastHooks.bind(created)
        LOGGER.info("Recast {} initialised", RecastRuntime.VERSION)
    }

    private fun stop() {
        val current = runtime ?: return
        runtime = null
        RecastHooks.unbind()
        current.shutdown()
    }

    private inline fun isolate(what: String, action: () -> Unit) {
        try {
            action()
        } catch (error: Throwable) {
            LOGGER.error("Recast {} failed", what, error)
        }
    }

    private companion object {
        val LOGGER = LogManager.getLogger("Recast")

        @Volatile
        var runtime: RecastRuntime? = null
    }
}
