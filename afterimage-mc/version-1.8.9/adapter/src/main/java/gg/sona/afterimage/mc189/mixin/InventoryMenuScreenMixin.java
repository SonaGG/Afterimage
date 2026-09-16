package gg.sona.afterimage.mc189.mixin;

import gg.sona.afterimage.mc189.AfterimageHooks;
import net.minecraft.client.gui.screen.inventory.menu.InventoryMenuScreen;
import net.minecraft.inventory.slot.InventorySlot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(InventoryMenuScreen.class)
public abstract class InventoryMenuScreenMixin {
    @Inject(method = "clickSlot(Lnet/minecraft/inventory/slot/InventorySlot;III)V", at = @At("HEAD"))
    private void afterimage$beforeSlotClick(InventorySlot slot, int slotId, int button, int mode, CallbackInfo callback) {
        AfterimageHooks.beforeSlotClick((InventoryMenuScreen) (Object) this);
    }

    @Inject(method = "clickSlot(Lnet/minecraft/inventory/slot/InventorySlot;III)V", at = @At("RETURN"))
    private void afterimage$afterSlotClick(InventorySlot slot, int slotId, int button, int mode, CallbackInfo callback) {
        AfterimageHooks.afterSlotClick((InventoryMenuScreen) (Object) this);
    }
}
