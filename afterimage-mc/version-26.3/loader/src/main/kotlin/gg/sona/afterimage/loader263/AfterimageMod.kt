package gg.sona.afterimage.loader263

import gg.sona.afterimage.mc263.AfterimageHooks
import gg.sona.afterimage.mc263.AfterimageRuntime
import net.fabricmc.api.ClientModInitializer
import org.slf4j.LoggerFactory

class AfterimageMod : ClientModInitializer {

    override fun onInitializeClient() {
        AfterimageHooks.install()
        LOGGER.info("Afterimage {} (26.3) initialised", AfterimageRuntime.VERSION)
    }

    private companion object {
        val LOGGER = LoggerFactory.getLogger("Afterimage")
    }
}
