package gg.sona.afterimage.mc26.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import gg.sona.afterimage.mc26.AfterimageHooks26;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Gui.class)
public abstract class GuiMixin {
    @Inject(method = "extractRenderState", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/Hud;extractSavingIndicator(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/DeltaTracker;)V"))
    private void afterimage$onHudExtracted(DeltaTracker deltaTracker, boolean shouldRenderLevel, boolean resourcesLoaded, CallbackInfo callback, @Local GuiGraphicsExtractor graphics) {
        AfterimageHooks26.onHudExtracted(graphics, deltaTracker.getGameTimeDeltaPartialTick(true));
    }

    @Inject(method = "setScreen", at = @At("HEAD"), cancellable = true)
    private void afterimage$keepReplayScreen(Screen screen, CallbackInfo callback) {
        if (AfterimageHooks26.keepsReplayScreen(screen)) {
            callback.cancel();
        }
    }

    @Redirect(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/Hud;tick(Z)V"))
    private void afterimage$hudTick(Hud hud, boolean pause) {
        if (!AfterimageHooks26.worldFrozen()) hud.tick(pause);
    }
}
