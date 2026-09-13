package gg.sona.recast.mc.mixin;

import gg.sona.recast.mc.RecastHooks;
import net.minecraft.client.render.block.entity.EndPortalRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(EndPortalRenderer.class)
public abstract class EndPortalRendererMixin {
    @Redirect(method = "render(Lnet/minecraft/block/entity/EndPortalBlockEntity;DDDFI)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;getTime()J"))
    private long recast$portalTime() {
        return RecastHooks.animationTime();
    }
}
