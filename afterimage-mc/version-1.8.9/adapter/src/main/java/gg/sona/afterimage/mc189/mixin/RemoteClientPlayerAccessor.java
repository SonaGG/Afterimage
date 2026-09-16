package gg.sona.afterimage.mc189.mixin;

import net.minecraft.client.entity.living.player.RemoteClientPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(RemoteClientPlayerEntity.class)
public interface RemoteClientPlayerAccessor {
    @Accessor("lerpSteps")
    int afterimage$remoteLerpSteps();

    @Accessor("lerpSteps")
    void afterimage$remoteLerpSteps(int steps);

    @Accessor("lerpX")
    double afterimage$remoteLerpX();

    @Accessor("lerpY")
    double afterimage$remoteLerpY();

    @Accessor("lerpZ")
    double afterimage$remoteLerpZ();

    @Accessor("lerpYaw")
    double afterimage$remoteLerpYaw();

    @Accessor("lerpPitch")
    double afterimage$remoteLerpPitch();
}
