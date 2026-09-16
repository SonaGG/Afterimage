package gg.sona.afterimage.mc189.mixin;

import gg.sona.afterimage.mc189.AfterimageHooks;
import net.minecraft.client.render.world.RenderChunkStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(RenderChunkStorage.class)
public abstract class RenderChunkStorageMixin {
    @Inject(method = "updateCameraPos(DD)V", at = @At("HEAD"), cancellable = true)
    private void afterimage$keepGridDuringSecondaryRender(double x, double z, CallbackInfo callback) {
        if (AfterimageHooks.secondaryRender()) {
            callback.cancel();
        }
    }
}
