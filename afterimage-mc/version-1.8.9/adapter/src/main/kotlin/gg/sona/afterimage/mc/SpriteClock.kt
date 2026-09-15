package gg.sona.afterimage.mc

import gg.sona.afterimage.mc.mixin.TextureAtlasAccessor
import gg.sona.afterimage.mc.mixin.TextureAtlasSpriteAccessor
import net.minecraft.client.Minecraft
import net.minecraft.client.render.platform.GlStateManager
import net.minecraft.client.render.texture.TextureAtlasSprite
import net.minecraft.client.render.texture.TextureUtil
import java.util.*

class SpriteClock(private val minecraft: Minecraft) {

    private val uploaded = IdentityHashMap<TextureAtlasSprite, Int>()

    fun align(tickIndex: Long) {
        val atlas = minecraft.blocksAtlas ?: return
        val sprites = (atlas as? TextureAtlasAccessor)?.`afterimage$animatedSprites`() ?: return
        var bound = false
        for (sprite in sprites) {
            val accessor = sprite as? TextureAtlasSpriteAccessor ?: continue
            val animation = accessor.`afterimage$animation`() ?: continue
            val frames = accessor.`afterimage$frames`()
            val count = if (animation.frameCount == 0) frames.size else animation.getFrameCount()
            if (count <= 0) continue
            var cycle = 0L
            for (index in 0 until count) cycle += animation.getFrameTime(index).coerceAtLeast(1)
            if (cycle <= 0L) continue
            var remaining = Math.floorMod(tickIndex, cycle)
            var active = 0
            while (true) {
                val length = animation.getFrameTime(active).coerceAtLeast(1)
                if (remaining < length) break
                remaining -= length
                active = (active + 1) % count
            }
            accessor.`afterimage$activeFrame`(active)
            accessor.`afterimage$frameTicks`(remaining.toInt())
            val frameIndex = animation.getFrameIndex(active)
            if (frameIndex < 0 || frameIndex >= frames.size) continue
            if (uploaded[sprite] != frameIndex) {
                if (!bound) {
                    GlStateManager.bindTexture(atlas.getGlId())
                    bound = true
                }
                TextureUtil.upload(
                    frames[frameIndex],
                    sprite.getWidth(),
                    sprite.getHeight(),
                    sprite.getX(),
                    sprite.getY(),
                    false,
                    false
                )
                uploaded[sprite] = frameIndex
            }
        }
    }

    fun observe() {
        val atlas = minecraft.blocksAtlas ?: return
        val sprites = (atlas as? TextureAtlasAccessor)?.`afterimage$animatedSprites`() ?: return
        for (sprite in sprites) {
            val accessor = sprite as? TextureAtlasSpriteAccessor ?: continue
            val animation = accessor.`afterimage$animation`() ?: continue
            uploaded[sprite] = animation.getFrameIndex(accessor.`afterimage$activeFrame`())
        }
    }

    fun forget() {
        uploaded.clear()
    }
}
