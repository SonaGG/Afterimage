package gg.sona.afterimage.mc189.mixin;

import net.minecraft.client.ParticleManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Random;

@Mixin(ParticleManager.class)
public interface ParticleManagerAccessor {
    @Accessor("random")
    Random afterimage$random();
}
