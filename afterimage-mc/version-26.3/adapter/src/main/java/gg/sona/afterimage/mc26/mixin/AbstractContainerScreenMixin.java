package gg.sona.afterimage.mc26.mixin;

import gg.sona.afterimage.mc26.AfterimageHooks26;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractContainerScreen.class)
public abstract class AbstractContainerScreenMixin {
    @Inject(method = "slotClicked(Lnet/minecraft/world/inventory/Slot;IILnet/minecraft/world/inventory/ContainerInput;)V", at = @At("HEAD"))
    private void afterimage$beforeSlotClick(Slot slot, int slotId, int buttonNum, ContainerInput containerInput, CallbackInfo callback) {
        AfterimageHooks26.beforeSlotClick((AbstractContainerScreen<?>) (Object) this);
    }

    @Inject(method = "slotClicked(Lnet/minecraft/world/inventory/Slot;IILnet/minecraft/world/inventory/ContainerInput;)V", at = @At("RETURN"))
    private void afterimage$afterSlotClick(Slot slot, int slotId, int buttonNum, ContainerInput containerInput, CallbackInfo callback) {
        AfterimageHooks26.afterSlotClick((AbstractContainerScreen<?>) (Object) this);
    }
}
