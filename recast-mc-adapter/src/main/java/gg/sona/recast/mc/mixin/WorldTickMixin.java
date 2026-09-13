package gg.sona.recast.mc.mixin;

import gg.sona.recast.mc.RecastHooks;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(World.class)
public abstract class WorldTickMixin {
    @Inject(method = "tickEntities", at = @At("HEAD"), cancellable = true)
    private void recast$freezeEntities(CallbackInfo callback) {
        if (RecastHooks.worldFrozen()) {
            callback.cancel();
        }
    }
}
