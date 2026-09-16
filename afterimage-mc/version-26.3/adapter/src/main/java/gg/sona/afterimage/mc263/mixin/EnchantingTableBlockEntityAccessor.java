package gg.sona.afterimage.mc263.mixin;

import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.entity.EnchantingTableBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(EnchantingTableBlockEntity.class)
public interface EnchantingTableBlockEntityAccessor {
    @Accessor("RANDOM")
    static RandomSource afterimage_random() {
        throw new AssertionError();
    }
}
