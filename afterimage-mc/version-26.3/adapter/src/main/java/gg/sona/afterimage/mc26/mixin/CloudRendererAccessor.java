package gg.sona.afterimage.mc26.mixin;

import net.minecraft.client.renderer.CloudRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(CloudRenderer.class)
public interface CloudRendererAccessor {
    @Accessor("texture")
    CloudRenderer.TextureData afterimage_texture();

    @Accessor("texture")
    void afterimage_setTexture(CloudRenderer.TextureData texture);
}
