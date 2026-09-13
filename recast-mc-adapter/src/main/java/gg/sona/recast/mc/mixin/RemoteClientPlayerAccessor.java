package gg.sona.recast.mc.mixin;

import net.minecraft.client.entity.living.player.RemoteClientPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(RemoteClientPlayerEntity.class)
public interface RemoteClientPlayerAccessor {
    @Accessor("lerpSteps")
    int recast$remoteLerpSteps();

    @Accessor("lerpSteps")
    void recast$remoteLerpSteps(int steps);

    @Accessor("lerpX")
    double recast$remoteLerpX();

    @Accessor("lerpY")
    double recast$remoteLerpY();

    @Accessor("lerpZ")
    double recast$remoteLerpZ();

    @Accessor("lerpYaw")
    double recast$remoteLerpYaw();

    @Accessor("lerpPitch")
    double recast$remoteLerpPitch();
}
