package gg.sona.afterimage.mc.mixin;

import gg.sona.afterimage.mc.AfterimageHooks;
import net.minecraft.client.world.ClientWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientWorld.class)
public abstract class ClientWorldMixin {
    @Redirect(method = "doRandomDisplayTicks(III)V", at = @At(value = "NEW", target = "java/util/Random"))
    private java.util.Random afterimage$displayRandom() {
        return AfterimageHooks.displayTickRandom();
    }

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void afterimage$freezeWorld(CallbackInfo callback) {
        if (AfterimageHooks.worldFrozen()) {
            callback.cancel();
        }
    }
}
