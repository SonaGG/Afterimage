package gg.sona.afterimage.mc189.mixin;

import gg.sona.afterimage.mc189.AfterimageHooks;
import net.minecraft.client.render.entity.ItemRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(ItemRenderer.class)
public abstract class ItemRendererMixin {
    @Redirect(method = "renderEnchantmentGlint(Lnet/minecraft/client/resource/model/BakedModel;)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;getTime()J"))
    private long afterimage$glintTime() {
        return AfterimageHooks.animationTime();
    }
}
