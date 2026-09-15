package gg.sona.afterimage.mc26.mixin;

import gg.sona.afterimage.mc26.AfterimageHooks26;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Hud.class)
public abstract class HudMixin {
    @Redirect(method = "extractPlayerHealth", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/Util;getMillis()J"))
    private long afterimage$healthTime() {
        return AfterimageHooks26.visualMillis();
    }

    @Inject(method = "extractItemHotbar", at = @At("HEAD"), cancellable = true)
    private void afterimage$hotbar(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker, CallbackInfo callback) {
        if (AfterimageHooks26.hideHudPart(AfterimageHooks26.HUD_HOTBAR)) callback.cancel();
    }

    @Inject(method = "extractPlayerHealth", at = @At("HEAD"), cancellable = true)
    private void afterimage$health(GuiGraphicsExtractor graphics, CallbackInfo callback) {
        if (AfterimageHooks26.hideHudPart(AfterimageHooks26.HUD_STATUS)) callback.cancel();
    }

    @Inject(method = "extractVehicleHealth", at = @At("HEAD"), cancellable = true)
    private void afterimage$vehicleHealth(GuiGraphicsExtractor graphics, CallbackInfo callback) {
        if (AfterimageHooks26.hideHudPart(AfterimageHooks26.HUD_STATUS)) callback.cancel();
    }

    @Inject(method = "extractBossOverlay", at = @At("HEAD"), cancellable = true)
    private void afterimage$boss(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker, CallbackInfo callback) {
        if (AfterimageHooks26.hideHudPart(AfterimageHooks26.HUD_BOSS)) callback.cancel();
    }

    @Inject(method = "extractScoreboardSidebar", at = @At("HEAD"), cancellable = true)
    private void afterimage$scoreboard(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker, CallbackInfo callback) {
        if (AfterimageHooks26.hideHudPart(AfterimageHooks26.HUD_SCOREBOARD)) callback.cancel();
    }

    @Inject(method = "extractVignette", at = @At("HEAD"), cancellable = true)
    private void afterimage$vignette(GuiGraphicsExtractor graphics, Entity camera, CallbackInfo callback) {
        if (AfterimageHooks26.hideHudPart(AfterimageHooks26.HUD_VIGNETTE)) callback.cancel();
    }

    @Inject(method = "extractChat", at = @At("HEAD"), cancellable = true)
    private void afterimage$chat(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker, CallbackInfo callback) {
        if (AfterimageHooks26.hideHudPart(AfterimageHooks26.HUD_CHAT)) callback.cancel();
    }
}
