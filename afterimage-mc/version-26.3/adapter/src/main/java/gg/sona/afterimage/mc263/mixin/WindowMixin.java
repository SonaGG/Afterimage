package gg.sona.afterimage.mc263.mixin;

import com.mojang.blaze3d.platform.Window;
import gg.sona.afterimage.mc263.AfterimageHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Window.class)
public abstract class WindowMixin {
    @Inject(method = "getWidth", at = @At("HEAD"), cancellable = true)
    private void afterimage$width(CallbackInfoReturnable<Integer> callback) {
        int[] size = AfterimageHooks.sizeOverride();
        if (size != null) callback.setReturnValue(size[0]);
    }

    @Inject(method = "getHeight", at = @At("HEAD"), cancellable = true)
    private void afterimage$height(CallbackInfoReturnable<Integer> callback) {
        int[] size = AfterimageHooks.sizeOverride();
        if (size != null) callback.setReturnValue(size[1]);
    }
}
