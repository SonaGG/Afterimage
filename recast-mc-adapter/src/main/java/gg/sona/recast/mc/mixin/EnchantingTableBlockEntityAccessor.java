package gg.sona.recast.mc.mixin;

import net.minecraft.block.entity.EnchantingTableBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Random;

@Mixin(EnchantingTableBlockEntity.class)
public interface EnchantingTableBlockEntityAccessor {
    @Accessor("RANDOM")
    static Random recast$random() {
        throw new AssertionError();
    }
}
