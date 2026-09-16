package gg.sona.afterimage.mc189.mixin;

import gg.sona.afterimage.mc189.AfterimageHooks;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(EntityRenderer.class)
public abstract class EntityRendererMixin {
    @Inject(method = "shouldRenderNameTag(Lnet/minecraft/entity/Entity;)Z", at = @At("HEAD"), cancellable = true)
    private void afterimage$hideNameTags(Entity entity, CallbackInfoReturnable<Boolean> callback) {
        if (AfterimageHooks.hideNametags() || AfterimageHooks.hideNametagFor(entity)) {
            callback.setReturnValue(false);
        }
    }
}
