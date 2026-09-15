package gg.sona.afterimage.mc26.replay

import gg.sona.afterimage.mc26.mixin.ClientLevelAccessor
import net.minecraft.client.multiplayer.ClientLevel

object LightSync26 {
    fun drain(level: ClientLevel) {
        val queue = (level as ClientLevelAccessor).afterimage_lightUpdateQueue()
        var guard = 0
        while (queue.isNotEmpty() && guard++ < MAX_ROUNDS) level.pollLightUpdates()
        level.chunkSource.lightEngine.runLightUpdates()
    }

    private const val MAX_ROUNDS = 10_000
}
