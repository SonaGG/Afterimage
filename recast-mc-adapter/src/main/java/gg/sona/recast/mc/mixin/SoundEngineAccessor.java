package gg.sona.recast.mc.mixin;

import net.minecraft.client.sound.Sound;
import net.minecraft.client.sound.SoundCategory;
import net.minecraft.client.sound.instance.SoundInstance;
import net.minecraft.client.sound.system.SoundEngine;
import net.minecraft.client.sound.system.SoundManager;
import net.minecraft.resource.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.net.URL;

@Mixin(SoundEngine.class)
public interface SoundEngineAccessor {
    @Accessor("manager")
    SoundManager recast$manager();

    @Invoker("getVolume")
    float recast$volume(SoundInstance instance, Sound sound, SoundCategory category);

    @Invoker("getPitch")
    float recast$pitch(SoundInstance instance, Sound sound);

    @Invoker("getSoundUrl")
    static URL recast$soundUrl(Identifier location) {
        throw new AssertionError();
    }
}
