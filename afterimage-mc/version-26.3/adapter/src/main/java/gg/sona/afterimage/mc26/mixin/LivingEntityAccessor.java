package gg.sona.afterimage.mc26.mixin;

import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(LivingEntity.class)
public interface LivingEntityAccessor {
    @Accessor("swingState")
    LivingEntity.SwingState afterimage_swingState();

    @Accessor("attackStrengthTicker")
    void afterimage_setAttackStrengthTicker(int ticks);

    @Accessor("itemSwapTicker")
    void afterimage_setItemSwapTicker(int ticks);
}
