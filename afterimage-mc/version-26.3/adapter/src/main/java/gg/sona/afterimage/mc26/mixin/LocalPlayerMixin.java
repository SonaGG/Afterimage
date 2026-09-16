package gg.sona.afterimage.mc26.mixin;

import gg.sona.afterimage.mc26.AfterimageHooks26;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LocalPlayer.class)
public abstract class LocalPlayerMixin {
    @Inject(method = "tick", at = @At("HEAD"))
    private void afterimage$onTick(CallbackInfo callback) {
        AfterimageHooks26.onLocalPlayerTick((LocalPlayer) (Object) this);
    }

    @Inject(method = "itemUsed", at = @At("HEAD"))
    private void afterimage$onItemUsed(InteractionHand hand, CallbackInfo callback) {
        AfterimageHooks26.onItemUsed((LocalPlayer) (Object) this, hand);
    }
}
