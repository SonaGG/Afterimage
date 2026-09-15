package gg.sona.afterimage.mc26.mixin;

import gg.sona.afterimage.mc26.AfterimageHooks26;
import java.util.function.BooleanSupplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.texture.TextureManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
    @Inject(method = "tick", at = @At("HEAD"))
    private void afterimage$onClientTickStart(CallbackInfo callback) {
        AfterimageHooks26.onClientTickStart();
    }

    @Inject(method = "tick", at = @At("RETURN"))
    private void afterimage$onClientTick(CallbackInfo callback) {
        AfterimageHooks26.onClientTick();
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void afterimage$onClose(CallbackInfo callback) {
        AfterimageHooks26.shutdown();
    }

    @Inject(method = "pick(F)V", at = @At("RETURN"))
    private void afterimage$afterPick(float partialTicks, CallbackInfo callback) {
        AfterimageHooks26.afterPick();
    }

    @Redirect(method = "setScreenAndShow", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;renderFrame(Z)V"))
    private void afterimage$forcedFrame(Minecraft minecraft, boolean advanceGameTime) {
        if (!AfterimageHooks26.suppressForcedFrames()) minecraft.renderFrame(advanceGameTime);
    }

    @Redirect(method = "runTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/texture/TextureManager;tick()V"))
    private void afterimage$textureTick(TextureManager manager) {
        if (!AfterimageHooks26.worldFrozen()) manager.tick();
    }

    @Redirect(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;tick()V"))
    private void afterimage$gameRendererTick(GameRenderer renderer) {
        if (!AfterimageHooks26.worldFrozen()) renderer.tick();
        else AfterimageHooks26.onFrozenRendererTick(renderer);
    }

    @Redirect(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/ClientLevel;tickEntities()V"))
    private void afterimage$tickEntities(ClientLevel level) {
        if (!AfterimageHooks26.worldFrozen()) level.tickEntities();
    }

    @Redirect(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/ClientLevel;tickBlockEntities()V"))
    private void afterimage$tickBlockEntities(ClientLevel level) {
        if (!AfterimageHooks26.worldFrozen()) level.tickBlockEntities();
    }

    @Redirect(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/ClientLevel;tick(Ljava/util/function/BooleanSupplier;)V"))
    private void afterimage$tickLevel(ClientLevel level, BooleanSupplier haveTime) {
        if (!AfterimageHooks26.worldFrozen()) level.tick(haveTime);
    }

    @Redirect(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/ClientLevel;animateTick(III)V"))
    private void afterimage$animateTick(ClientLevel level, int x, int y, int z) {
        if (!AfterimageHooks26.worldFrozen()) level.animateTick(x, y, z);
    }

    @Redirect(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/particle/ParticleEngine;tick()V"))
    private void afterimage$tickParticles(ParticleEngine engine) {
        if (!AfterimageHooks26.worldFrozen()) engine.tick();
    }
}
