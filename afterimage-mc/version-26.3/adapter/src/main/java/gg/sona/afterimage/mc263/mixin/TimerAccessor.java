package gg.sona.afterimage.mc263.mixin;

import net.minecraft.client.DeltaTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(DeltaTracker.Timer.class)
public interface TimerAccessor {
    @Accessor("deltaTickResidual")
    void afterimage_setDeltaTickResidual(float value);

    @Accessor("deltaTickResidual")
    float afterimage_deltaTickResidual();
}
