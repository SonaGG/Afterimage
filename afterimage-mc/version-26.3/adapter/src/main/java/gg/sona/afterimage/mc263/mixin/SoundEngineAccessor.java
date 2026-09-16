package gg.sona.afterimage.mc263.mixin;

import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.client.sounds.SoundManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(SoundEngine.class)
public interface SoundEngineAccessor {
    @Accessor("soundManager")
    SoundManager afterimage_soundManager();

    @Invoker("calculateVolume")
    float afterimage_calculateVolume(SoundInstance instance);

    @Invoker("calculatePitch")
    float afterimage_calculatePitch(SoundInstance instance);
}
