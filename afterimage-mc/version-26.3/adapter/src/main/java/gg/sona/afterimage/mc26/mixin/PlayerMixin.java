package gg.sona.afterimage.mc26.mixin;

import gg.sona.afterimage.mc26.AfterimageHooks26;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Player.class)
public abstract class PlayerMixin {
    @Redirect(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Player;isSpectator()Z", ordinal = 0))
    private boolean afterimage$cameraNoPhysics(Player player) {
        return player.isSpectator() || AfterimageHooks26.cameraNoPhysics(player);
    }

    @Inject(method = "resetAttackStrengthTicker", at = @At("HEAD"))
    private void afterimage$onAttackReset(CallbackInfo callback) {
        AfterimageHooks26.onAttackReset((Player) (Object) this);
    }
}
