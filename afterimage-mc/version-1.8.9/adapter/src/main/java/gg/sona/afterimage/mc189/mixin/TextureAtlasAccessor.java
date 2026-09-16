package gg.sona.afterimage.mc189.mixin;

import net.minecraft.client.render.texture.TextureAtlas;
import net.minecraft.client.render.texture.TextureAtlasSprite;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

@Mixin(TextureAtlas.class)
public interface TextureAtlasAccessor {
    @Accessor("animatedSprites")
    List<TextureAtlasSprite> afterimage$animatedSprites();
}
