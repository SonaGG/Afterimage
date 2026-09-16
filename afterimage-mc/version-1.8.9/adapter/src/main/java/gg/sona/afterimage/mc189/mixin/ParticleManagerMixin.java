package gg.sona.afterimage.mc189.mixin;

import gg.sona.afterimage.mc189.AfterimageHooks;
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
    private void afterimage$freezeParticles(CallbackInfo callback) {
        if (AfterimageHooks.worldFrozen()) {
            callback.cancel();
        }
    }

    @Inject(
            method = "addParticle(IDDDDDD[I)Lnet/minecraft/client/entity/particle/Particle;",
            at = @At("HEAD"),
            cancellable = true
    )
    private void afterimage$hideParticleType(
            int type, double x, double y, double z, double velocityX, double velocityY, double velocityZ, int[] parameters,
            CallbackInfoReturnable<Particle> callback
    ) {
        if (AfterimageHooks.hideParticleType(type)) {
            callback.setReturnValue(null);
        }
    }

    @Inject(method = "render(Lnet/minecraft/entity/Entity;F)V", at = @At("HEAD"), cancellable = true)
    private void afterimage$hideParticles(Entity camera, float tickDelta, CallbackInfo callback) {
        if (AfterimageHooks.hideParticles()) {
            callback.cancel();
        }
    }

    @Inject(method = "renderLit(Lnet/minecraft/entity/Entity;F)V", at = @At("HEAD"), cancellable = true)
    private void afterimage$hideLitParticles(Entity camera, float tickDelta, CallbackInfo callback) {
        if (AfterimageHooks.hideParticles()) {
            callback.cancel();
        }
    }
}
