package gg.sona.afterimage.mc26.mixin;

import gg.sona.afterimage.mc26.AfterimageHooks26;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(SoundEngine.class)
public abstract class SoundEngineMixin {
    @Inject(method = "play", at = @At("HEAD"), cancellable = true)
    private void afterimage$onPlay(SoundInstance instance, CallbackInfoReturnable<SoundEngine.PlayResult> callback) {
        if (AfterimageHooks26.onSoundPlay((SoundEngine) (Object) this, instance)) {
            callback.setReturnValue(SoundEngine.PlayResult.NOT_STARTED);
        }
    }
}
