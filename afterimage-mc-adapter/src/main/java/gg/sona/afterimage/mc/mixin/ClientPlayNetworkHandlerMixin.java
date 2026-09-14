package gg.sona.afterimage.mc.mixin;

import com.mojang.authlib.GameProfile;
import gg.sona.afterimage.mc.AfterimageHooks;
import gg.sona.afterimage.mc.ReplayConnectionOwner;
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
    public Connection afterimage$connection() {
        return connection;
    }

    @Inject(method = "<init>", at = @At("RETURN"))
    private void afterimage$onPlayConnection(Minecraft minecraft, Screen screen, Connection connection, GameProfile profile, CallbackInfo callback) {
        AfterimageHooks.onPlayConnection(connection);
    }

    @Redirect(method = {"handleLogin", "handlePlayerRespawn"}, at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;openScreen(Lnet/minecraft/client/gui/screen/Screen;)V"))
    private void afterimage$skipTerrainScreen(Minecraft minecraft, Screen screen) {
        if (AfterimageHooks.isReplayConnection(connection)) {
            AfterimageHooks.onReplayWorldSwitch();
            return;
        }
        minecraft.openScreen(screen);
    }

    @Redirect(method = {"handlePlayerMove", "handleGameEvent", "handleCustomPayload"}, at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;openScreen(Lnet/minecraft/client/gui/screen/Screen;)V"))
    private void afterimage$keepReplayScreen(Minecraft minecraft, Screen screen) {
        if (AfterimageHooks.isReplayConnection(connection) && AfterimageHooks.keepsReplayScreen(screen)) return;
        minecraft.openScreen(screen);
    }

    @Inject(method = "onDisconnect", at = @At("HEAD"), cancellable = true)
    private void afterimage$onDisconnect(Text reason, CallbackInfo callback) {
        if (AfterimageHooks.isReplayConnection(connection)) {
            AfterimageHooks.onReplayDisconnectSuppressed(reason);
            callback.cancel();
            return;
        }
        AfterimageHooks.onPlayDisconnect(connection);
    }
}
