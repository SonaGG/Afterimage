package gg.sona.afterimage.mc26.mixin;

import net.minecraft.client.particle.SpellParticle;
import net.minecraft.util.RandomSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(SpellParticle.class)
public interface SpellParticleAccessor {
    @Accessor("RANDOM")
    static RandomSource afterimage_random() {
        throw new AssertionError();
    }
}
