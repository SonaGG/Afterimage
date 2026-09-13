package gg.sona.recast.mc.mixin;

import net.minecraft.entity.living.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(LivingEntity.class)
public interface LivingEntityAccessor {

    @Accessor("lerpSteps")
    int recast$lerpSteps();

    @Accessor("lerpSteps")
    void recast$lerpSteps(int steps);

    @Accessor("lerpX")
    double recast$lerpX();

    @Accessor("lerpY")
    double recast$lerpY();

    @Accessor("lerpZ")
    double recast$lerpZ();

    @Accessor("lerpYaw")
    double recast$lerpYaw();

    @Accessor("lerpPitch")
    double recast$lerpPitch();
}
