package gg.sona.recast.mc.mixin;

import com.mojang.authlib.GameProfile;
import gg.sona.recast.mc.RecastHooks;
import gg.sona.recast.mc.ReplayConnectionOwner;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.network.handler.ClientPlayNetworkHandler;
import net.minecraft.network.Connection;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPlayNetworkHandler.class)
public abstract class ClientPlayNetworkHandlerMixin implements ReplayConnectionOwner {
    @Shadow
    @Final
    private Connection connection;

    @Override
    public Connection recast$connection() {
        return connection;
    }

    @Inject(method = "<init>", at = @At("RETURN"))
    private void recast$onPlayConnection(Minecraft minecraft, Screen screen, Connection connection, GameProfile profile, CallbackInfo callback) {
        RecastHooks.onPlayConnection(connection);
    }

    @Redirect(method = {"handleLogin", "handlePlayerRespawn"}, at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;openScreen(Lnet/minecraft/client/gui/screen/Screen;)V"))
    private void recast$skipTerrainScreen(Minecraft minecraft, Screen screen) {
        if (RecastHooks.isReplayConnection(connection)) {
            RecastHooks.onReplayWorldSwitch();
            return;
        }
        minecraft.openScreen(screen);
    }

    @Redirect(method = {"handlePlayerMove", "handleGameEvent", "handleCustomPayload"}, at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;openScreen(Lnet/minecraft/client/gui/screen/Screen;)V"))
    private void recast$keepReplayScreen(Minecraft minecraft, Screen screen) {
        if (RecastHooks.isReplayConnection(connection) && RecastHooks.keepsReplayScreen(screen)) return;
        minecraft.openScreen(screen);
    }

    @Inject(method = "onDisconnect", at = @At("HEAD"), cancellable = true)
    private void recast$onDisconnect(Text reason, CallbackInfo callback) {
        if (RecastHooks.isReplayConnection(connection)) {
            RecastHooks.onReplayDisconnectSuppressed(reason);
            callback.cancel();
            return;
        }
        RecastHooks.onPlayDisconnect(connection);
    }
}
