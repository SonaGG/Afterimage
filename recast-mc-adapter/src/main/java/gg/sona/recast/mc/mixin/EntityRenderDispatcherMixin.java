package gg.sona.recast.mc.mixin;

import gg.sona.recast.mc.RecastHooks;
import net.minecraft.client.render.entity.EntityRenderDispatcher;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRenderDispatcherMixin {
    @Shadow
    private double offsetX;

    @Shadow
    private double offsetY;

    @Shadow
    private double offsetZ;

    @Inject(method = "render(Lnet/minecraft/entity/Entity;DDDFFZ)Z", at = @At("HEAD"), cancellable = true)
    private void recast$hideCameraTarget(Entity entity, double x, double y, double z, float yaw, float tickDelta, boolean hideBoundingBox, CallbackInfoReturnable<Boolean> callback) {
        if (RecastHooks.shouldHideEntity(entity)) {
            callback.setReturnValue(false);
        }
    }

    @Inject(method = "render(Lnet/minecraft/entity/Entity;FZ)Z", at = @At("HEAD"))
    private void recast$beforeEntityRender(Entity entity, float tickDelta, boolean hideBoundingBox, CallbackInfoReturnable<Boolean> callback) {
        RecastHooks.beforeEntityRender(entity);
    }

    @ModifyArgs(method = "render(Lnet/minecraft/entity/Entity;FZ)Z", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/entity/EntityRenderDispatcher;render(Lnet/minecraft/entity/Entity;DDDFFZ)Z"))
    private void recast$smoothPosition(Args args) {
        Entity entity = args.get(0);
        double[] position = RecastHooks.smoothedPosition(entity);
        if (position == null) {
            return;
        }
        args.set(1, position[0] - offsetX);
        args.set(2, position[1] - offsetY);
        args.set(3, position[2] - offsetZ);
        args.set(4, (float) position[3]);
    }
}
