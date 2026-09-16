package gg.sona.afterimage.mc189.mixin;

import net.minecraft.entity.living.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(LivingEntity.class)
public interface LivingEntityAccessor {

    @Accessor("lerpSteps")
    int afterimage$lerpSteps();

    @Accessor("lerpSteps")
    void afterimage$lerpSteps(int steps);

    @Accessor("lerpX")
    double afterimage$lerpX();

    @Accessor("lerpY")
    double afterimage$lerpY();

    @Accessor("lerpZ")
    double afterimage$lerpZ();

    @Accessor("lerpYaw")
    double afterimage$lerpYaw();

    @Accessor("lerpPitch")
    double afterimage$lerpPitch();
}
