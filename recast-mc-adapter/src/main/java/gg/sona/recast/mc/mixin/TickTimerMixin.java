package gg.sona.recast.mc.mixin;

import gg.sona.recast.mc.RecastHooks;
import net.minecraft.client.TickTimer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TickTimer.class)
public abstract class TickTimerMixin {
    @Inject(method = "advance", at = @At("RETURN"))
    private void recast$onAdvanced(CallbackInfo callback) {
        RecastHooks.onTimerAdvanced((TickTimer) (Object) this);
    }
}
