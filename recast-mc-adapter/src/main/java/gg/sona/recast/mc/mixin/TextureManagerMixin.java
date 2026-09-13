package gg.sona.recast.mc.mixin;

import gg.sona.recast.mc.RecastHooks;
import net.minecraft.client.render.texture.TextureManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TextureManager.class)
public abstract class TextureManagerMixin {
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void recast$freezeAnimations(CallbackInfo callback) {
        if (RecastHooks.worldFrozen()) {
            callback.cancel();
        }
    }
}
