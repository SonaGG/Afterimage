package gg.sona.afterimage.mc263.mixin;

import net.minecraft.world.entity.AbstractInterpolationHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(AbstractInterpolationHandler.class)
public interface InterpolationHandlerAccessor {
    @Accessor("interpolationSteps")
    int afterimage_interpolationSteps();

    @Accessor("interpolationSteps")
    void afterimage_setInterpolationSteps(int steps);
}
