package gg.sona.recast.mc.mixin;

import gg.sona.recast.mc.RecastHooks;
import net.minecraft.client.render.model.entity.HumanoidModel;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(HumanoidModel.class)
public abstract class HumanoidModelMixin {
    @Inject(method = "setupAnimation(FFFFFFLnet/minecraft/entity/Entity;)V", at = @At("HEAD"))
    private void recast$restorePose(float walkProgress, float walkSpeed, float age, float headYaw, float pitch, float scale, Entity entity, CallbackInfo callback) {
        RecastHooks.restorePose((HumanoidModel) (Object) this);
    }

    @Inject(method = "setupAnimation(FFFFFFLnet/minecraft/entity/Entity;)V", at = @At("RETURN"))
    private void recast$applyPose(float walkProgress, float walkSpeed, float age, float headYaw, float pitch, float scale, Entity entity, CallbackInfo callback) {
        RecastHooks.applyPose((HumanoidModel) (Object) this, entity);
    }
}
