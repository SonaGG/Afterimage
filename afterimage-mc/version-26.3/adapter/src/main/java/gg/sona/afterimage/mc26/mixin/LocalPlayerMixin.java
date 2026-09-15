package gg.sona.afterimage.mc26.mixin;

import gg.sona.afterimage.mc26.AfterimageHooks26;
import net.minecraft.client.player.LocalPlayer;
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
}
