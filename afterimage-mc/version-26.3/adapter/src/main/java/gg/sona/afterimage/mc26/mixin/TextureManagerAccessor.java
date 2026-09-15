package gg.sona.afterimage.mc26.mixin;

import java.util.Set;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.client.renderer.texture.TickableTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(TextureManager.class)
public interface TextureManagerAccessor {
    @Accessor("tickableTextures")
    Set<TickableTexture> afterimage_tickableTextures();
}
