package gg.sona.afterimage.mc26.mixin;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Entity.class)
public interface EntityDataIdsAccessor {
    @Accessor("DATA_SHARED_FLAGS_ID")
    static EntityDataAccessor<Byte> afterimage_sharedFlags() {
        throw new AssertionError();
    }

    @Accessor("DATA_AIR_SUPPLY_ID")
    static EntityDataAccessor<Integer> afterimage_airSupply() {
        throw new AssertionError();
    }
}
