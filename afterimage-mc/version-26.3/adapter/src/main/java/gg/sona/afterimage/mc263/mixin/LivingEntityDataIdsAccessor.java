package gg.sona.afterimage.mc263.mixin;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(LivingEntity.class)
public interface LivingEntityDataIdsAccessor {
    @Accessor("DATA_HEALTH_ID")
    static EntityDataAccessor<Float> afterimage_health() {
        throw new AssertionError();
    }
}
