package gg.sona.afterimage.mc189.mixin;

import gg.sona.afterimage.mc189.AfterimageHooks;
import net.minecraft.client.ClientPlayerInteractionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ClientPlayerInteractionManager.class)
public abstract class ClientPlayerInteractionManagerMixin {
    @Inject(method = {"finishMiningBlock", "useBlock", "useItem"}, at = @At("HEAD"))
    private void afterimage$localEditBegin(CallbackInfoReturnable<Boolean> callback) {
        AfterimageHooks.localEditBegin();
    }

    @Inject(method = {"finishMiningBlock", "useBlock", "useItem"}, at = @At("RETURN"))
    private void afterimage$localEditEnd(CallbackInfoReturnable<Boolean> callback) {
        AfterimageHooks.localEditEnd();
    }
}
