package gg.sona.afterimage.mc189.mixin;

import gg.sona.afterimage.mc189.AfterimageHooks;
import net.minecraft.client.render.texture.TextureManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TextureManager.class)
public abstract class TextureManagerMixin {
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void afterimage$freezeAnimations(CallbackInfo callback) {
        if (AfterimageHooks.worldFrozen()) {
            callback.cancel();
        }
    }
}
