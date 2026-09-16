package gg.sona.afterimage.mc263.mixin;

import gg.sona.afterimage.mc263.AfterimageHooks;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.player.AbstractClientPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Camera.class)
public abstract class CameraMixin {
    @Inject(method = "update", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;alignWithEntity(F)V", shift = At.Shift.AFTER))
    private void afterimage$onAligned(DeltaTracker deltaTracker, CallbackInfo callback) {
        AfterimageHooks.onCameraUpdated((Camera) (Object) this);
    }

    @Redirect(method = "tickFov", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/AbstractClientPlayer;getFieldOfViewModifier(ZF)F"))
    private float afterimage$fovModifier(AbstractClientPlayer player, boolean firstPerson, float effectScale) {
        return AfterimageHooks.fovModifier(player, player.getFieldOfViewModifier(firstPerson, effectScale));
    }

    @Inject(method = "calculateFov", at = @At("RETURN"), cancellable = true)
    private void afterimage$fov(float partialTicks, CallbackInfoReturnable<Float> callback) {
        float override = AfterimageHooks.cameraFov();
        if (override > 0f) {
            callback.setReturnValue(override);
        }
    }
}
