package gg.sona.recast.mc.mixin;

import net.minecraft.client.render.ItemInHandRenderer;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ItemInHandRenderer.class)
public interface ItemInHandRendererAccessor {
    @Accessor("itemInHand")
    void recast$setItemInHand(ItemStack stack);

    @Accessor("handHeight")
    void recast$setHandHeight(float value);

    @Accessor("lastHandHeight")
    void recast$setLastHandHeight(float value);

    @Accessor("selectedSlot")
    void recast$setSelectedSlot(int slot);
}
