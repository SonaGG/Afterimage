package gg.sona.afterimage.mc263.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.resource.CrossFrameResourcePool;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LightmapRenderStateExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(GameRenderer.class)
public interface GameRendererAccessor {
    @Accessor("lightmapRenderStateExtractor")
    LightmapRenderStateExtractor afterimage_lightmapExtractor();

    @Accessor("resourcePool")
    CrossFrameResourcePool afterimage_resourcePool();

    @Accessor("mainRenderTarget")
    @Mutable
    void afterimage_setMainRenderTarget(RenderTarget target);
}
