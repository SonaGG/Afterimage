package gg.sona.recast.mc.mixin;

import gg.sona.recast.mc.RecastHooks;
import net.minecraft.client.ClientPlayerInteractionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ClientPlayerInteractionManager.class)
public abstract class ClientPlayerInteractionManagerMixin {
    @Inject(method = {"finishMiningBlock", "useBlock", "useItem"}, at = @At("HEAD"))
    private void recast$localEditBegin(CallbackInfoReturnable<Boolean> callback) {
        RecastHooks.localEditBegin();
    }

    @Inject(method = {"finishMiningBlock", "useBlock", "useItem"}, at = @At("RETURN"))
    private void recast$localEditEnd(CallbackInfoReturnable<Boolean> callback) {
        RecastHooks.localEditEnd();
    }
}
