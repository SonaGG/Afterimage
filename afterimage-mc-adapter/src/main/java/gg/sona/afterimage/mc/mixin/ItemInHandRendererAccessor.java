package gg.sona.afterimage.mc.mixin;

import net.minecraft.client.render.ItemInHandRenderer;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ItemInHandRenderer.class)
public interface ItemInHandRendererAccessor {
    @Accessor("itemInHand")
    void afterimage$setItemInHand(ItemStack stack);

    @Accessor("handHeight")
    void afterimage$setHandHeight(float value);

    @Accessor("lastHandHeight")
    void afterimage$setLastHandHeight(float value);

    @Accessor("selectedSlot")
    void afterimage$setSelectedSlot(int slot);
}
