package gg.sona.afterimage.mc26.mixin;

import gg.sona.afterimage.mc26.AfterimageHooks26;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.registries.BuiltInRegistries;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ParticleEngine.class)
public abstract class ParticleEngineMixin {
    @Inject(method = "add", at = @At("HEAD"), cancellable = true)
    private void afterimage$hideParticles(Particle p, CallbackInfo callback) {
        if (AfterimageHooks26.hideParticles()) {
            callback.cancel();
        }
    }

    @Inject(method = "createParticle", at = @At("HEAD"), cancellable = true)
    private void afterimage$hideParticleType(ParticleOptions options, double x, double y, double z, double xa, double ya, double za, CallbackInfoReturnable<Particle> callback) {
        if (AfterimageHooks26.hideParticles() || AfterimageHooks26.hideParticleType(BuiltInRegistries.PARTICLE_TYPE.getId(options.getType()))) {
            callback.setReturnValue(null);
        }
    }
}
