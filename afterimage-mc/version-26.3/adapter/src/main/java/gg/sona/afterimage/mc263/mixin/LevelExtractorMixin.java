package gg.sona.afterimage.mc263.mixin;

import gg.sona.afterimage.mc263.AfterimageHooks;
import net.minecraft.client.renderer.extract.LevelExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelExtractor.class)
public abstract class LevelExtractorMixin {
    @Redirect(method = "drainTransientBlockQueue", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/Util;getMillis()J"))
    private long afterimage$transientTime() {
        return AfterimageHooks.visualMillis();
    }

    @Inject(method = "allChanged", at = @At("HEAD"), cancellable = true)
    private void afterimage$keepSharedGeometry(CallbackInfo callback) {
        if (AfterimageHooks.isPreviewExtractor((LevelExtractor) (Object) this)) callback.cancel();
    }
}
