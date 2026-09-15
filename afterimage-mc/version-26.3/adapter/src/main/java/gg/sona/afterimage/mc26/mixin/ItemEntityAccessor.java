package gg.sona.afterimage.mc26.mixin;

import net.minecraft.world.entity.item.ItemEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ItemEntity.class)
public interface ItemEntityAccessor {
    @Accessor("age")
    void afterimage_setAge(int age);

    @Accessor("bobOffs")
    @org.spongepowered.asm.mixin.Mutable
    void afterimage_setBobOffs(float bobOffs);
}
