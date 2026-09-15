package gg.sona.afterimage.loader26

import gg.sona.afterimage.mc26.AfterimageHooks26
import gg.sona.afterimage.mc26.AfterimageRuntime26
import net.fabricmc.api.ClientModInitializer
import org.slf4j.LoggerFactory

class AfterimageMod : ClientModInitializer {

    override fun onInitializeClient() {
        AfterimageHooks26.install()
        LOGGER.info("Afterimage {} (26.3) initialised", AfterimageRuntime26.VERSION)
    }

    private companion object {
        val LOGGER = LoggerFactory.getLogger("Afterimage")
    }
}
