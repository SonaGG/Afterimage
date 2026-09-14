package gg.sona.afterimage.mc.mixin;

import net.minecraft.client.render.texture.TextureAtlasSprite;
import net.minecraft.client.resource.metadata.AnimationMetadata;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

@Mixin(TextureAtlasSprite.class)
public interface TextureAtlasSpriteAccessor {
    @Accessor("frames")
    List<int[][]> afterimage$frames();

    @Accessor("animation")
    AnimationMetadata afterimage$animation();

    @Accessor("activeFrame")
    int afterimage$activeFrame();

    @Accessor("activeFrame")
    void afterimage$activeFrame(int value);

    @Accessor("frameTicks")
    void afterimage$frameTicks(int value);
}
