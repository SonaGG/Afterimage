package gg.sona.recast.mc.mixin;

import net.minecraft.client.entity.particle.SpellParticle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Random;

@Mixin(SpellParticle.class)
public interface SpellParticleAccessor {
    @Accessor("RANDOM")
    static Random recast$random() {
        throw new AssertionError();
    }
}
