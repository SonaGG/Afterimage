package gg.sona.afterimage.mc263.replay

import gg.sona.afterimage.mc263.mixin.ClientLevelAccessor
import net.minecraft.client.multiplayer.ClientLevel

object LightSync {
    fun drain(level: ClientLevel) {
        val queue = (level as ClientLevelAccessor).afterimage_lightUpdateQueue()
        var guard = 0
        while (queue.isNotEmpty() && guard++ < MAX_ROUNDS) level.pollLightUpdates()
        level.chunkSource.lightEngine.runLightUpdates()
    }

    private const val MAX_ROUNDS = 10_000
}
