package gg.sona.recast.mc.mixin;

import gg.sona.recast.mc.RecastHooks;
import net.minecraft.client.render.entity.ItemRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(ItemRenderer.class)
public abstract class ItemRendererMixin {
    @Redirect(method = "renderEnchantmentGlint(Lnet/minecraft/client/resource/model/BakedModel;)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;getTime()J"))
    private long recast$glintTime() {
        return RecastHooks.animationTime();
    }
}
