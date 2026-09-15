package gg.sona.afterimage.loader

import gg.sona.afterimage.mc.AfterimageHooks
import gg.sona.afterimage.mc.AfterimageRuntime
import gg.sona.afterimage.protocol47.Protocol47Support
import net.fabricmc.api.ClientModInitializer
import net.minecraft.client.Minecraft
import net.ornithemc.osl.lifecycle.api.client.MinecraftClientEvents
import net.ornithemc.osl.networking.api.client.ClientConnectionEvents
import org.apache.logging.log4j.LogManager

class AfterimageMod : ClientModInitializer {

    override fun onInitializeClient() {
        Protocol47Support.install()
        MinecraftClientEvents.READY.register { minecraft -> start(minecraft) }
        MinecraftClientEvents.TICK_END.register { runTask("client tick") { runtime?.onClientTick() } }
        MinecraftClientEvents.STOP.register { runTask("shutdown") { stop() } }
        ClientConnectionEvents.DISCONNECT.register { runTask("disconnect") { runtime?.onDisconnect() } }
    }

    private fun start(minecraft: Minecraft) {
        if (runtime != null) return
        val created = AfterimageRuntime(minecraft)
        runtime = created
        AfterimageHooks.bind(created)
        LOGGER.info("Afterimage {} initialised", AfterimageRuntime.VERSION)
    }

    private fun stop() {
        val current = runtime ?: return
        runtime = null
        AfterimageHooks.unbind()
        current.shutdown()
    }

    private inline fun runTask(what: String, action: () -> Unit) {
        try {
            action()
        } catch (error: Throwable) {
            LOGGER.error("Afterimage {} failed", what, error)
        }
    }

    private companion object {
        val LOGGER = LogManager.getLogger("Afterimage")

        @Volatile
        var runtime: AfterimageRuntime? = null
    }
}
