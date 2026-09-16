package gg.sona.afterimage.mc263.mixin;

import net.minecraft.client.renderer.LightmapRenderStateExtractor;
import net.minecraft.util.RandomSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(LightmapRenderStateExtractor.class)
public interface LightmapRenderStateExtractorAccessor {
    @Accessor("randomSource")
    RandomSource afterimage_random();
}
