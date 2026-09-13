package gg.sona.recast.mc.mixin;

import net.minecraft.client.render.texture.TextureAtlasSprite;
import net.minecraft.client.resource.metadata.AnimationMetadata;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

@Mixin(TextureAtlasSprite.class)
public interface TextureAtlasSpriteAccessor {
    @Accessor("frames")
    List<int[][]> recast$frames();

    @Accessor("animation")
    AnimationMetadata recast$animation();

    @Accessor("activeFrame")
    int recast$activeFrame();

    @Accessor("activeFrame")
    void recast$activeFrame(int value);

    @Accessor("frameTicks")
    void recast$frameTicks(int value);
}
