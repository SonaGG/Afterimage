package gg.sona.afterimage.mc26.replay

import gg.sona.afterimage.mc26.mixin.AbstractTextureAccessor
import gg.sona.afterimage.mc26.mixin.AnimationStateAccessor
import gg.sona.afterimage.mc26.mixin.TextureAtlasAccessor
import gg.sona.afterimage.mc26.mixin.TextureManagerAccessor
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.texture.TextureAtlas

class SpriteClock26(private val minecraft: Minecraft) {
    fun align(tickIndex: Long) {
        val tickables = (minecraft.textureManager as TextureManagerAccessor).afterimage_tickableTextures()
        for (texture in tickables.toList()) {
            if (texture !is TextureAtlas) {
                texture.tick()
                continue
            }
            val atlas = texture as TextureAtlasAccessor
            if ((texture as AbstractTextureAccessor).afterimage_texture() == null) continue
            for (state in atlas.afterimage_animationStates()) {
                val accessor = state as AnimationStateAccessor
                val frames = accessor.afterimage_animationInfo().frames
                if (frames.isEmpty()) continue
                var total = 0L
                for (frame in frames) total += frame.time().coerceAtLeast(1)
                var remaining = Math.floorMod(tickIndex, total)
                var index = 0
                while (index < frames.size - 1) {
                    val time = frames[index].time().coerceAtLeast(1)
                    if (remaining < time) break
                    remaining -= time
                    index++
                }
                val previous = accessor.afterimage_frame().coerceIn(0, frames.size - 1)
                accessor.afterimage_setDirty(frames[previous].index() != frames[index].index())
                accessor.afterimage_setFrame(index)
                accessor.afterimage_setSubFrame(remaining.toInt())
            }
            atlas.afterimage_uploadAnimationFrames()
        }
    }
}
