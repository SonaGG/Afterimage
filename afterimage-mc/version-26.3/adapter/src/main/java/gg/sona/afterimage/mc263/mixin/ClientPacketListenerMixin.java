package gg.sona.afterimage.mc263.mixin;

import gg.sona.afterimage.mc263.AfterimageHooks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.CommonListenerCookie;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundSetPlayerInventoryPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {
    @Inject(method = "<init>", at = @At("RETURN"))
    private void afterimage$onPlayConnection(Minecraft minecraft, Connection connection, CommonListenerCookie cookie, CallbackInfo callback) {
        AfterimageHooks.onPlayConnection(connection);
    }

    @Inject(method = "handleContainerSetSlot", at = @At("HEAD"), cancellable = true)
    private void afterimage$setSlotInPlace(ClientboundContainerSetSlotPacket packet, CallbackInfo callback) {
        if (packet.getContainerId() == 0 && AfterimageHooks.updateInventoryInPlace(true, packet.getSlot(), packet.getItem())) {
            callback.cancel();
        }
    }

    @Inject(method = "handleSetPlayerInventory", at = @At("HEAD"), cancellable = true)
    private void afterimage$setInventoryInPlace(ClientboundSetPlayerInventoryPacket packet, CallbackInfo callback) {
        if (AfterimageHooks.updateInventoryInPlace(false, packet.slot(), packet.contents())) {
            callback.cancel();
        }
    }

    @Inject(method = "handleAddEntity", at = @At("RETURN"))
    private void afterimage$onEntitySpawned(ClientboundAddEntityPacket packet, CallbackInfo callback) {
        AfterimageHooks.onEntitySpawned(packet.getId());
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void afterimage$onClose(CallbackInfo callback) {
        AfterimageHooks.onDisconnect();
    }
}
