package gg.sona.afterimage.mc26.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import gg.sona.afterimage.mc26.AfterimageHooks26;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.Projection;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.GameRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.FirstPersonHandsAndItemsRenderState;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    @Shadow
    @Final
    private GameRenderState gameRenderState;

    @Shadow
    @Final
    private Camera mainCamera;

    @Inject(method = "update", at = @At("HEAD"))
    private void afterimage$onFrameStart(DeltaTracker deltaTracker, CallbackInfo callback) {
        AfterimageHooks26.onFrameStart(deltaTracker.getGameTimeDeltaPartialTick(true));
    }

    @Inject(method = "extract", at = @At("RETURN"))
    private void afterimage$onExtracted(DeltaTracker deltaTracker, boolean advanceGameTime, CallbackInfo callback) {
        AfterimageHooks26.onExtracted(this.gameRenderState);
    }

    @Inject(method = "extractWindow", at = @At("RETURN"))
    private void afterimage$exportSize(CallbackInfo callback) {
        int[] size = AfterimageHooks26.sizeOverride();
        if (size != null) {
            this.gameRenderState.windowRenderState.width = size[0];
            this.gameRenderState.windowRenderState.height = size[1];
        }
    }

    @Inject(method = "extractCamera", at = @At("RETURN"))
    private void afterimage$onCameraExtracted(DeltaTracker deltaTracker, float worldPartialTicks, CallbackInfo callback) {
        AfterimageHooks26.onCameraExtracted(this.gameRenderState.levelRenderState.cameraRenderState, this.mainCamera.getFov());
    }

    @ModifyArg(method = "renderLevel", at = @At(value = "INVOKE", target = "Lorg/joml/Matrix4f;mul(Lorg/joml/Matrix4fc;)Lorg/joml/Matrix4f;", ordinal = 0))
    private Matrix4fc afterimage$captureBob(Matrix4fc bob) {
        AfterimageHooks26.onBob(bob);
        return bob;
    }

    @Inject(method = "shouldRenderBlockOutline", at = @At("RETURN"), cancellable = true)
    private void afterimage$forceBlockOutline(CallbackInfoReturnable<Boolean> callback) {
        if (!callback.getReturnValueZ() && AfterimageHooks26.forceBlockOutline()) {
            callback.setReturnValue(true);
        }
    }

    @Inject(method = "bobHurt", at = @At("HEAD"), cancellable = true)
    private void afterimage$suppressHurtCamera(CameraRenderState cameraState, PoseStack poseStack, CallbackInfo callback) {
        if (AfterimageHooks26.suppressHurtCamera()) {
            callback.cancel();
        }
    }

    @Redirect(
            method = "renderItemInHand",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/FirstPersonHandsAndItemsRenderer;submitHandsWithItems(FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/PlayerRenderState;Lnet/minecraft/client/renderer/state/level/FirstPersonHandsAndItemsRenderState;)V")
    )
    private void afterimage$submitHands(FirstPersonHandsAndItemsRenderer renderer, float partialTicks, PoseStack poseStack, SubmitNodeCollector collector, PlayerRenderState playerState, FirstPersonHandsAndItemsRenderState handsState) {
        CameraRenderState cameraState = this.gameRenderState.levelRenderState.cameraRenderState;
        Matrix4fc pose = AfterimageHooks26.handPose(cameraState.viewRotationMatrix, poseStack.last().pose(), cameraState);
        if (AfterimageHooks26.hideHand()) {
            return;
        }
        if (pose != poseStack.last().pose()) {
            poseStack.last().pose().set(pose);
        }
        renderer.submitHandsWithItems(partialTicks, poseStack, collector, playerState, handsState);
    }

    @ModifyArg(
            method = "renderLevel",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/LevelRenderer;render(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;ZLnet/minecraft/client/renderer/state/level/CameraRenderState;Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;Lorg/joml/Vector4f;ZZ)V"),
            index = 5
    )
    private boolean afterimage$renderSky(boolean shouldRenderSky) {
        return AfterimageHooks26.renderSky(shouldRenderSky);
    }

    @ModifyArg(
            method = "renderLevel",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/LevelRenderer;render(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;ZLnet/minecraft/client/renderer/state/level/CameraRenderState;Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;Lorg/joml/Vector4f;ZZ)V"),
            index = 4
    )
    private Vector4f afterimage$fogColor(Vector4f fogColor) {
        return AfterimageHooks26.fogColor(fogColor);
    }

    @Redirect(
            method = "render3dHud",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/ProjectionMatrixBuffer;getBuffer(Lnet/minecraft/client/renderer/Projection;)Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;")
    )
    private GpuBufferSlice afterimage$hudProjection(ProjectionMatrixBuffer buffer, Projection projection) {
        return buffer.getBuffer(AfterimageHooks26.hudProjection(projection.getMatrix(new Matrix4f())));
    }

    @Inject(
            method = "renderLevel",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;render3dHud(Lnet/minecraft/client/renderer/state/level/CameraRenderState;Lnet/minecraft/client/renderer/state/level/PlayerRenderState;Lnet/minecraft/client/renderer/state/OptionsRenderState;Z)V")
    )
    private void afterimage$onWorldRendered(CallbackInfo callback) {
        AfterimageHooks26.onWorldRendered();
    }

    @Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/fog/FogRenderer;endFrame()V"))
    private void afterimage$onLevelRendered(CallbackInfo callback) {
        AfterimageHooks26.onLevelRendered();
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void afterimage$onFrameEnd(CallbackInfo callback) {
        AfterimageHooks26.onFrameEnd();
    }
}
