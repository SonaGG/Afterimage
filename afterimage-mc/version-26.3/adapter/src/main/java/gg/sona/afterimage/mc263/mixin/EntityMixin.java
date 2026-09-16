package gg.sona.afterimage.mc263.mixin;

import gg.sona.afterimage.mc263.AfterimageHooks;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Entity.class)
public abstract class EntityMixin {
    @Inject(method = "<init>", at = @At("RETURN"))
    private void afterimage$seed(CallbackInfo callback) {
        AfterimageHooks.onEntityCreated((Entity) (Object) this);
    }

    @Inject(method = "getPosition(F)Lnet/minecraft/world/phys/Vec3;", at = @At("HEAD"), cancellable = true)
    private void afterimage$smoothedPosition(float partialTickTime, CallbackInfoReturnable<Vec3> callback) {
        Vec3 smoothed = AfterimageHooks.smoothedPosition((Entity) (Object) this);
        if (smoothed != null) {
            callback.setReturnValue(smoothed);
        }
    }
}
