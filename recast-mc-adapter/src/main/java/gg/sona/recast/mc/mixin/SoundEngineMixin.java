package gg.sona.recast.mc.mixin;

import gg.sona.recast.mc.RecastHooks;
import net.minecraft.client.sound.instance.SoundInstance;
import net.minecraft.client.sound.system.SoundEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SoundEngine.class)
public abstract class SoundEngineMixin {
    @Inject(method = "play(Lnet/minecraft/client/sound/instance/SoundInstance;)V", at = @At("HEAD"), cancellable = true)
    private void recast$onPlay(SoundInstance sound, CallbackInfo callback) {
        if (RecastHooks.onSoundPlay((SoundEngine) (Object) this, sound)) {
            callback.cancel();
        }
    }
}
