package gg.sona.afterimage.mc263.mixin;

import gg.sona.afterimage.mc263.AfterimageHooks;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.util.RandomSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {
    @Redirect(method = "submitBlockDestroyAnimation", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/RandomSource;createThreadLocalInstance()Lnet/minecraft/util/RandomSource;"))
    private RandomSource afterimage$crackRandom() {
        return AfterimageHooks.frameRandom();
    }

    @Redirect(method = "submitTransientBlocks", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/Util;getMillis()J"))
    private long afterimage$transientTime() {
        return AfterimageHooks.visualMillis();
    }

    @Inject(method = "repositionCamera", at = @At("HEAD"), cancellable = true)
    private void afterimage$keepMainCamera(CameraRenderState camera, CallbackInfo callback) {
        if (AfterimageHooks.previewRendering()) callback.cancel();
    }

    @Inject(method = "compileSections", at = @At("HEAD"), cancellable = true)
    private void afterimage$skipPreviewCompile(CameraRenderState camera, CallbackInfo callback) {
        if (AfterimageHooks.previewRendering()) callback.cancel();
    }
}
