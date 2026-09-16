package gg.sona.afterimage.mc263.mixin;

import gg.sona.afterimage.mc263.AfterimageHooks;
import net.minecraft.client.DeltaTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(DeltaTracker.Timer.class)
public abstract class TimerMixin {
    @Inject(method = "advanceGameTime", at = @At("RETURN"))
    private void afterimage$onAdvanced(long currentMs, CallbackInfoReturnable<Integer> callback) {
        AfterimageHooks.onTimerAdvanced((TimerAccessor) this);
    }
}
