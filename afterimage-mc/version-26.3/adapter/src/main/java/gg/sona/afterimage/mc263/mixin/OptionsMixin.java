package gg.sona.afterimage.mc263.mixin;

import gg.sona.afterimage.mc263.AfterimageHooks;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Options.class)
public abstract class OptionsMixin {
    @Inject(method = "save", at = @At("HEAD"))
    private void afterimage$beforeSave(CallbackInfo callback) {
        AfterimageHooks.beforeOptionsSave();
    }

    @Inject(method = "save", at = @At("RETURN"))
    private void afterimage$afterSave(CallbackInfo callback) {
        AfterimageHooks.afterOptionsSave();
    }
}
