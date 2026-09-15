package gg.sona.afterimage.mc26.mixin;

import gg.sona.afterimage.mc26.replay.ReplayConnections26;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientCommonPacketListenerImpl.class)
public abstract class ClientCommonPacketListenerImplMixin {
    @Final
    @Shadow
    protected Connection connection;

    @Inject(method = "onDisconnect", at = @At("HEAD"), cancellable = true)
    private void afterimage$ignoreReplayDisconnect(DisconnectionDetails details, CallbackInfo callback) {
        if (ReplayConnections26.isReplay(this.connection)) callback.cancel();
    }
}
