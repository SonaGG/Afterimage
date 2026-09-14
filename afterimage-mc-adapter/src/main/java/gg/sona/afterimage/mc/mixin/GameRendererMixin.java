package gg.sona.afterimage.mc.mixin;

import gg.sona.afterimage.mc.AfterimageHooks;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.platform.GlStateManager;
import net.minecraft.client.render.world.WorldRenderer;
import org.lwjgl.opengl.GL11;
import org.lwjgl.util.glu.Project;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    @Inject(method = "render(IFJ)V", at = @At(value = "INVOKE_STRING", target = "Lnet/minecraft/util/profiler/Profiler;swap(Ljava/lang/String;)V", args = "ldc=hand"))
    private void afterimage$worldPassEnd(int pass, float tickDelta, long finishNanos, CallbackInfo callback) {
        AfterimageHooks.onWorldPassEnd();
    }

    @Inject(method = "tick()V", at = @At("HEAD"), cancellable = true)
    private void afterimage$freezeRendererTick(CallbackInfo callback) {
        if (AfterimageHooks.worldFrozen()) {
            callback.cancel();
        }
    }

    @Inject(method = "render(FJ)V", at = @At("HEAD"))
    private void afterimage$onFrameStart(float tickDelta, long nanoTime, CallbackInfo callback) {
        AfterimageHooks.onFrameStart(tickDelta);
    }

    @Inject(method = "render(FJ)V", at = @At("RETURN"))
    private void afterimage$onFrameEnd(float tickDelta, long nanoTime, CallbackInfo callback) {
        AfterimageHooks.onFrameEnd();
    }

    @Inject(method = "renderWorld(FJ)V", at = @At("RETURN"))
    private void afterimage$onWorldPassComplete(float tickDelta, long finishNanos, CallbackInfo callback) {
        AfterimageHooks.onWorldPassComplete(tickDelta, finishNanos);
    }

    @Inject(method = "getFov(FZ)F", at = @At("RETURN"), cancellable = true)
    private void afterimage$fov(float tickDelta, boolean useFovSetting, CallbackInfoReturnable<Float> callback) {
        if (!useFovSetting) {
            return;
        }
        AfterimageHooks.observeFov(callback.getReturnValueF());
        float override = AfterimageHooks.cameraFov();
        if (override > 0f) {
            callback.setReturnValue(override);
        }
    }

    @Inject(method = "pick(F)V", at = @At("RETURN"))
    private void afterimage$afterPick(float tickDelta, CallbackInfo callback) {
        AfterimageHooks.afterPick();
    }

    @Inject(method = "shouldRenderBlockOutline()Z", at = @At("RETURN"), cancellable = true)
    private void afterimage$forceBlockOutline(CallbackInfoReturnable<Boolean> callback) {
        if (!callback.getReturnValue() && AfterimageHooks.forceBlockOutline()) {
            callback.setReturnValue(true);
        }
    }

    @Inject(method = "setupCamera(FI)V", at = @At("RETURN"))
    private void afterimage$onCameraSetup(float tickDelta, int pass, CallbackInfo callback) {
        AfterimageHooks.onCameraSetup(tickDelta);
    }

    @Redirect(
            method = "setupCamera(FI)V",
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/util/glu/Project;gluPerspective(FFFF)V")
    )
    private void afterimage$maybeOrthographic(float fovy, float aspect, float zNear, float zFar) {
        Float orthoScale = AfterimageHooks.exportOrthoScale();
        if (orthoScale == null) {
            Project.gluPerspective(fovy, aspect, zNear, zFar);
            return;
        }
        float halfHeight = orthoScale;
        float halfWidth = halfHeight * aspect;
        GL11.glOrtho(-halfWidth, halfWidth, -halfHeight, halfHeight, -zFar, zFar);
    }

    @Inject(method = "applyHurtCam(F)V", at = @At("HEAD"), cancellable = true)
    private void afterimage$gateHurtCamera(float tickDelta, CallbackInfo callback) {
        if (AfterimageHooks.suppressHurtCamera()) {
            callback.cancel();
        }
    }

    @Inject(method = "transformCamera(F)V", at = @At("HEAD"))
    private void afterimage$applyCameraRoll(float tickDelta, CallbackInfo callback) {
        float roll = AfterimageHooks.cameraRoll();
        if (roll != 0f) {
            GlStateManager.rotatef(roll, 0f, 0f, 1f);
        }
    }

    @Inject(method = "renderClouds(Lnet/minecraft/client/render/world/WorldRenderer;FI)V", at = @At("HEAD"), cancellable = true)
    private void afterimage$hideClouds(WorldRenderer renderer, float tickDelta, int pass, CallbackInfo callback) {
        if (AfterimageHooks.hideClouds()) {
            callback.cancel();
        }
    }

    @Inject(method = "renderSnowAndRain(F)V", at = @At("HEAD"), cancellable = true)
    private void afterimage$hideWeather(float tickDelta, CallbackInfo callback) {
        if (AfterimageHooks.hideWeather()) {
            callback.cancel();
        }
    }

    @Inject(method = "setupFog(IF)V", at = @At("RETURN"))
    private void afterimage$fog(int mode, float tickDelta, CallbackInfo callback) {
        AfterimageHooks.onFogSetup();
    }

    @Inject(method = "setupClearColor(F)V", at = @At("RETURN"))
    private void afterimage$clearColor(float tickDelta, CallbackInfo callback) {
        AfterimageHooks.onClearColor();
    }

    @Inject(method = "renderItemInHand(FI)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/ItemInHandRenderer;renderInFirstPerson(F)V"))
    private void afterimage$onHandSetup(float tickDelta, int pass, CallbackInfo callback) {
        AfterimageHooks.onHandSetup();
    }

    @Inject(method = "renderItemInHand(FI)V", at = @At("HEAD"), cancellable = true)
    private void afterimage$hideHandWhenDetached(float tickDelta, int pass, CallbackInfo callback) {
        AfterimageHooks.onWorldRendered(tickDelta);
        if (AfterimageHooks.hideHand()) {
            callback.cancel();
        }
    }
}
