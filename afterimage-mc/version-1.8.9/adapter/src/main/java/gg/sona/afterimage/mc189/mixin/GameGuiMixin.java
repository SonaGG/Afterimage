package gg.sona.afterimage.mc189.mixin;

import gg.sona.afterimage.mc189.AfterimageHooks;
import net.minecraft.client.gui.GameGui;
import net.minecraft.client.render.Window;
import net.minecraft.scoreboard.ScoreboardObjective;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameGui.class)
public abstract class GameGuiMixin {
    @Inject(method = "renderVignette(FLnet/minecraft/client/render/Window;)V", at = @At("HEAD"))
    private void afterimage$vignetteBegin(float brightness, Window window, CallbackInfo callback) {
        AfterimageHooks.vignetteBegin((GameGui) (Object) this);
    }

    @Inject(method = "renderVignette(FLnet/minecraft/client/render/Window;)V", at = @At("RETURN"))
    private void afterimage$vignetteEnd(float brightness, Window window, CallbackInfo callback) {
        AfterimageHooks.vignetteEnd((GameGui) (Object) this);
    }

    @Redirect(method = "renderStatusBars(Lnet/minecraft/client/render/Window;)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;getTime()J"))
    private long afterimage$healthFlashTime() {
        return AfterimageHooks.animationTime();
    }

    @Inject(method = "tick()V", at = @At("HEAD"), cancellable = true)
    private void afterimage$freezeHudTick(CallbackInfo callback) {
        if (AfterimageHooks.worldFrozen()) {
            callback.cancel();
        }
    }

    @Inject(method = "render(F)V", at = @At("HEAD"), cancellable = true)
    private void afterimage$onHudRender(float tickDelta, CallbackInfo callback) {
        if (AfterimageHooks.onHudRender()) {
            callback.cancel();
        }
    }

    @Inject(method = "render(F)V", at = @At("TAIL"))
    private void afterimage$onHudRendered(float tickDelta, CallbackInfo callback) {
        AfterimageHooks.onHudRendered(tickDelta);
    }

    @Inject(method = "renderHotbar(Lnet/minecraft/client/render/Window;F)V", at = @At("HEAD"), cancellable = true)
    private void afterimage$hotbar(Window window, float tickDelta, CallbackInfo callback) {
        if (AfterimageHooks.hideHudPart(AfterimageHooks.HUD_HOTBAR)) {
            callback.cancel();
        }
    }

    @Inject(method = "renderStatusBars(Lnet/minecraft/client/render/Window;)V", at = @At("HEAD"), cancellable = true)
    private void afterimage$statusBars(Window window, CallbackInfo callback) {
        if (AfterimageHooks.hideHudPart(AfterimageHooks.HUD_STATUS)) {
            callback.cancel();
        }
    }

    @Inject(method = "renderXpBar(Lnet/minecraft/client/render/Window;I)V", at = @At("HEAD"), cancellable = true)
    private void afterimage$xpBar(Window window, int x, CallbackInfo callback) {
        if (AfterimageHooks.hideHudPart(AfterimageHooks.HUD_EXPERIENCE)) {
            callback.cancel();
        }
    }

    @Inject(method = "renderBossBars()V", at = @At("HEAD"), cancellable = true)
    private void afterimage$bossBars(CallbackInfo callback) {
        if (AfterimageHooks.hideHudPart(AfterimageHooks.HUD_BOSS)) {
            callback.cancel();
        }
    }

    @Inject(method = "renderScoreboardObjective(Lnet/minecraft/scoreboard/ScoreboardObjective;Lnet/minecraft/client/render/Window;)V", at = @At("HEAD"), cancellable = true)
    private void afterimage$scoreboard(ScoreboardObjective objective, Window window, CallbackInfo callback) {
        if (AfterimageHooks.hideHudPart(AfterimageHooks.HUD_SCOREBOARD)) {
            callback.cancel();
        }
    }

    @Inject(method = "renderVignette(FLnet/minecraft/client/render/Window;)V", at = @At("HEAD"), cancellable = true)
    private void afterimage$vignette(float brightness, Window window, CallbackInfo callback) {
        if (AfterimageHooks.hideHudPart(AfterimageHooks.HUD_VIGNETTE)) {
            callback.cancel();
        }
    }
}
