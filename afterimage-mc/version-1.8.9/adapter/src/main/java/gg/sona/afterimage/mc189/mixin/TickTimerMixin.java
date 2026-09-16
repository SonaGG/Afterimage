package gg.sona.afterimage.mc189.mixin;

import gg.sona.afterimage.mc189.AfterimageHooks;
import net.minecraft.client.TickTimer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TickTimer.class)
public abstract class TickTimerMixin {
    @Inject(method = "advance", at = @At("RETURN"))
    private void afterimage$onAdvanced(CallbackInfo callback) {
        AfterimageHooks.onTimerAdvanced((TickTimer) (Object) this);
    }
}
