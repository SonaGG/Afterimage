package gg.sona.afterimage.mc.mixin;

import gg.sona.afterimage.mc.AfterimageHooks;
import net.minecraft.client.render.model.entity.HumanoidModel;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(HumanoidModel.class)
public abstract class HumanoidModelMixin {
    @Inject(method = "setupAnimation(FFFFFFLnet/minecraft/entity/Entity;)V", at = @At("HEAD"))
    private void afterimage$restorePose(float walkProgress, float walkSpeed, float age, float headYaw, float pitch, float scale, Entity entity, CallbackInfo callback) {
        AfterimageHooks.restorePose((HumanoidModel) (Object) this);
    }

    @Inject(method = "setupAnimation(FFFFFFLnet/minecraft/entity/Entity;)V", at = @At("RETURN"))
    private void afterimage$applyPose(float walkProgress, float walkSpeed, float age, float headYaw, float pitch, float scale, Entity entity, CallbackInfo callback) {
        AfterimageHooks.applyPose((HumanoidModel) (Object) this, entity);
    }
}
