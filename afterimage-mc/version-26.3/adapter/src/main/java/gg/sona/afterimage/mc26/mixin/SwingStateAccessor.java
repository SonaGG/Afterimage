package gg.sona.afterimage.mc26.mixin;

import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(LivingEntity.SwingState.class)
public interface SwingStateAccessor {
    @Accessor("currentSwing")
    LivingEntity.SwingDescription afterimage_currentSwing();

    @Accessor("currentSwing")
    void afterimage_setCurrentSwing(LivingEntity.SwingDescription swing);

    @Accessor("ticks")
    int afterimage_ticks();

    @Accessor("ticks")
    void afterimage_setTicks(int ticks);

    @Accessor("animation")
    float afterimage_animation();

    @Accessor("animation")
    void afterimage_setAnimation(float animation);

    @Accessor("oldAnimation")
    float afterimage_oldAnimation();

    @Accessor("oldAnimation")
    void afterimage_setOldAnimation(float animation);
}
