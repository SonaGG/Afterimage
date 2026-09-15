package gg.sona.afterimage.mc.mixin;

import gg.sona.afterimage.mc.AfterimageHooks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.render.pipeline.RenderTarget;
import net.minecraft.client.world.ClientWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
    @Inject(method = "getRenderTarget", at = @At("HEAD"), cancellable = true)
    private void afterimage$exportTarget(CallbackInfoReturnable<RenderTarget> callback) {
        RenderTarget override = AfterimageHooks.renderTargetOverride();
        if (override != null) {
            callback.setReturnValue(override);
        }
    }

    @Inject(method = "tick()V", at = @At("HEAD"))
    private void afterimage$onTickStart(CallbackInfo callback) {
        AfterimageHooks.onClientTickStart();
    }

    @Redirect(
            method = "tick()V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/world/ClientWorld;doRandomDisplayTicks(III)V")
    )
    private void afterimage$ambientParticles(ClientWorld world, int x, int y, int z) {
        if (!AfterimageHooks.worldFrozen()) {
            world.doRandomDisplayTicks(x, y, z);
        }
    }
}
