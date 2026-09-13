package gg.sona.recast.mc.mixin;

import gg.sona.recast.mc.RecastHooks;
import net.minecraft.client.gui.screen.inventory.menu.InventoryMenuScreen;
import net.minecraft.inventory.slot.InventorySlot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(InventoryMenuScreen.class)
public abstract class InventoryMenuScreenMixin {
    @Inject(method = "clickSlot(Lnet/minecraft/inventory/slot/InventorySlot;III)V", at = @At("HEAD"))
    private void recast$beforeSlotClick(InventorySlot slot, int slotId, int button, int mode, CallbackInfo callback) {
        RecastHooks.beforeSlotClick((InventoryMenuScreen) (Object) this);
    }

    @Inject(method = "clickSlot(Lnet/minecraft/inventory/slot/InventorySlot;III)V", at = @At("RETURN"))
    private void recast$afterSlotClick(InventorySlot slot, int slotId, int button, int mode, CallbackInfo callback) {
        RecastHooks.afterSlotClick((InventoryMenuScreen) (Object) this);
    }
}
