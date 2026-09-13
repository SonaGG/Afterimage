package gg.sona.recast.mc.mixin;

import gg.sona.recast.mc.RecastHooks;
import net.minecraft.client.ParticleManager;
import net.minecraft.client.entity.particle.Particle;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ParticleManager.class)
public abstract class ParticleManagerMixin {
    @Inject(method = "tick()V", at = @At("HEAD"), cancellable = true)
    private void recast$freezeParticles(CallbackInfo callback) {
        if (RecastHooks.worldFrozen()) {
            callback.cancel();
        }
    }

    @Inject(
            method = "addParticle(IDDDDDD[I)Lnet/minecraft/client/entity/particle/Particle;",
            at = @At("HEAD"),
            cancellable = true
    )
    private void recast$hideParticleType(
            int type, double x, double y, double z, double velocityX, double velocityY, double velocityZ, int[] parameters,
            CallbackInfoReturnable<Particle> callback
    ) {
        if (RecastHooks.hideParticleType(type)) {
            callback.setReturnValue(null);
        }
    }

    @Inject(method = "render(Lnet/minecraft/entity/Entity;F)V", at = @At("HEAD"), cancellable = true)
    private void recast$hideParticles(Entity camera, float tickDelta, CallbackInfo callback) {
        if (RecastHooks.hideParticles()) {
            callback.cancel();
        }
    }

    @Inject(method = "renderLit(Lnet/minecraft/entity/Entity;F)V", at = @At("HEAD"), cancellable = true)
    private void recast$hideLitParticles(Entity camera, float tickDelta, CallbackInfo callback) {
        if (RecastHooks.hideParticles()) {
            callback.cancel();
        }
    }
}
