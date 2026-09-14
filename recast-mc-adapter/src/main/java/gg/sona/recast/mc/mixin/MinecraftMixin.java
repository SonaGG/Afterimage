package gg.sona.recast.mc.mixin;

import gg.sona.recast.mc.RecastHooks;
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
    private void recast$exportTarget(CallbackInfoReturnable<RenderTarget> callback) {
        RenderTarget override = RecastHooks.renderTargetOverride();
        if (override != null) {
            callback.setReturnValue(override);
        }
    }

    @Inject(method = "tick()V", at = @At("HEAD"))
    private void recast$onTickStart(CallbackInfo callback) {
        RecastHooks.onClientTickStart();
    }

    @Redirect(
            method = "tick()V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/world/ClientWorld;doRandomDisplayTicks(III)V")
    )
    private void recast$ambientParticles(ClientWorld world, int x, int y, int z) {
        if (!RecastHooks.worldFrozen()) {
            world.doRandomDisplayTicks(x, y, z);
        }
    }
}
