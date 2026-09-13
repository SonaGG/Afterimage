package gg.sona.recast.mc.mixin;

import gg.sona.recast.mc.RecastHooks;
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
    private void recast$worldPassEnd(int pass, float tickDelta, long finishNanos, CallbackInfo callback) {
        RecastHooks.onWorldPassEnd();
    }

    @Inject(method = "tick()V", at = @At("HEAD"), cancellable = true)
    private void recast$freezeRendererTick(CallbackInfo callback) {
        if (RecastHooks.worldFrozen()) {
            callback.cancel();
        }
    }

    @Inject(method = "render(FJ)V", at = @At("HEAD"))
    private void recast$onFrameStart(float tickDelta, long nanoTime, CallbackInfo callback) {
        RecastHooks.onFrameStart(tickDelta);
    }

    @Inject(method = "render(FJ)V", at = @At("RETURN"))
    private void recast$onFrameEnd(float tickDelta, long nanoTime, CallbackInfo callback) {
        RecastHooks.onFrameEnd();
    }

    @Inject(method = "renderWorld(FJ)V", at = @At("RETURN"))
    private void recast$onWorldPassComplete(float tickDelta, long finishNanos, CallbackInfo callback) {
        RecastHooks.onWorldPassComplete(tickDelta, finishNanos);
    }

    @Inject(method = "getFov(FZ)F", at = @At("RETURN"), cancellable = true)
    private void recast$fov(float tickDelta, boolean useFovSetting, CallbackInfoReturnable<Float> callback) {
        if (!useFovSetting) {
            return;
        }
        RecastHooks.observeFov(callback.getReturnValueF());
        float override = RecastHooks.cameraFov();
        if (override > 0f) {
            callback.setReturnValue(override);
        }
    }

    @Inject(method = "pick(F)V", at = @At("RETURN"))
    private void recast$afterPick(float tickDelta, CallbackInfo callback) {
        RecastHooks.afterPick();
    }

    @Inject(method = "shouldRenderBlockOutline()Z", at = @At("RETURN"), cancellable = true)
    private void recast$forceBlockOutline(CallbackInfoReturnable<Boolean> callback) {
        if (!callback.getReturnValue() && RecastHooks.forceBlockOutline()) {
            callback.setReturnValue(true);
        }
    }

    @Inject(method = "setupCamera(FI)V", at = @At("RETURN"))
    private void recast$onCameraSetup(float tickDelta, int pass, CallbackInfo callback) {
        RecastHooks.onCameraSetup(tickDelta);
    }

    @Redirect(
            method = "setupCamera(FI)V",
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/util/glu/Project;gluPerspective(FFFF)V")
    )
    private void recast$maybeOrthographic(float fovy, float aspect, float zNear, float zFar) {
        Float orthoScale = RecastHooks.exportOrthoScale();
        if (orthoScale == null) {
            Project.gluPerspective(fovy, aspect, zNear, zFar);
            return;
        }
        float halfHeight = orthoScale;
        float halfWidth = halfHeight * aspect;
        GL11.glOrtho(-halfWidth, halfWidth, -halfHeight, halfHeight, -zFar, zFar);
    }

    @Inject(method = "applyHurtCam(F)V", at = @At("HEAD"), cancellable = true)
    private void recast$gateHurtCamera(float tickDelta, CallbackInfo callback) {
        if (RecastHooks.suppressHurtCamera()) {
            callback.cancel();
        }
    }

    @Inject(method = "transformCamera(F)V", at = @At("HEAD"))
    private void recast$applyCameraRoll(float tickDelta, CallbackInfo callback) {
        float roll = RecastHooks.cameraRoll();
        if (roll != 0f) {
            GlStateManager.rotatef(roll, 0f, 0f, 1f);
        }
    }

    @Inject(method = "renderClouds(Lnet/minecraft/client/render/world/WorldRenderer;FI)V", at = @At("HEAD"), cancellable = true)
    private void recast$hideClouds(WorldRenderer renderer, float tickDelta, int pass, CallbackInfo callback) {
        if (RecastHooks.hideClouds()) {
            callback.cancel();
        }
    }

    @Inject(method = "renderSnowAndRain(F)V", at = @At("HEAD"), cancellable = true)
    private void recast$hideWeather(float tickDelta, CallbackInfo callback) {
        if (RecastHooks.hideWeather()) {
            callback.cancel();
        }
    }

    @Inject(method = "setupFog(IF)V", at = @At("RETURN"))
    private void recast$fog(int mode, float tickDelta, CallbackInfo callback) {
        RecastHooks.onFogSetup();
    }

    @Inject(method = "setupClearColor(F)V", at = @At("RETURN"))
    private void recast$clearColor(float tickDelta, CallbackInfo callback) {
        RecastHooks.onClearColor();
    }

    @Inject(method = "renderItemInHand(FI)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/ItemInHandRenderer;renderInFirstPerson(F)V"))
    private void recast$onHandSetup(float tickDelta, int pass, CallbackInfo callback) {
        RecastHooks.onHandSetup();
    }

    @Inject(method = "renderItemInHand(FI)V", at = @At("HEAD"), cancellable = true)
    private void recast$hideHandWhenDetached(float tickDelta, int pass, CallbackInfo callback) {
        RecastHooks.onWorldRendered(tickDelta);
        if (RecastHooks.hideHand()) {
            callback.cancel();
        }
    }
}
