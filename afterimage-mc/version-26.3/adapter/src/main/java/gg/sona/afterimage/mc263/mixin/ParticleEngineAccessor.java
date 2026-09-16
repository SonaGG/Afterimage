package gg.sona.afterimage.mc263.mixin;

import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.util.RandomSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ParticleEngine.class)
public interface ParticleEngineAccessor {
    @Accessor("random")
    RandomSource afterimage_random();
}
