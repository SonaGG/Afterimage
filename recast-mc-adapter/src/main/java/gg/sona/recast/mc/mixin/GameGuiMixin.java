package gg.sona.recast.mc.mixin;

import gg.sona.recast.mc.RecastHooks;
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
    private void recast$vignetteBegin(float brightness, Window window, CallbackInfo callback) {
        RecastHooks.vignetteBegin((GameGui) (Object) this);
    }

    @Inject(method = "renderVignette(FLnet/minecraft/client/render/Window;)V", at = @At("RETURN"))
    private void recast$vignetteEnd(float brightness, Window window, CallbackInfo callback) {
        RecastHooks.vignetteEnd((GameGui) (Object) this);
    }

    @Redirect(method = "renderStatusBars(Lnet/minecraft/client/render/Window;)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;getTime()J"))
    private long recast$healthFlashTime() {
        return RecastHooks.animationTime();
    }

    @Inject(method = "tick()V", at = @At("HEAD"), cancellable = true)
    private void recast$freezeHudTick(CallbackInfo callback) {
        if (RecastHooks.worldFrozen()) {
            callback.cancel();
        }
    }

    @Inject(method = "render(F)V", at = @At("HEAD"), cancellable = true)
    private void recast$onHudRender(float tickDelta, CallbackInfo callback) {
        if (RecastHooks.onHudRender()) {
            callback.cancel();
        }
    }

    @Inject(method = "render(F)V", at = @At("TAIL"))
    private void recast$onHudRendered(float tickDelta, CallbackInfo callback) {
        RecastHooks.onHudRendered(tickDelta);
    }

    @Inject(method = "renderHotbar(Lnet/minecraft/client/render/Window;F)V", at = @At("HEAD"), cancellable = true)
    private void recast$hotbar(Window window, float tickDelta, CallbackInfo callback) {
        if (RecastHooks.hideHudPart(RecastHooks.HUD_HOTBAR)) {
            callback.cancel();
        }
    }

    @Inject(method = "renderStatusBars(Lnet/minecraft/client/render/Window;)V", at = @At("HEAD"), cancellable = true)
    private void recast$statusBars(Window window, CallbackInfo callback) {
        if (RecastHooks.hideHudPart(RecastHooks.HUD_STATUS)) {
            callback.cancel();
        }
    }

    @Inject(method = "renderXpBar(Lnet/minecraft/client/render/Window;I)V", at = @At("HEAD"), cancellable = true)
    private void recast$xpBar(Window window, int x, CallbackInfo callback) {
        if (RecastHooks.hideHudPart(RecastHooks.HUD_EXPERIENCE)) {
            callback.cancel();
        }
    }

    @Inject(method = "renderBossBars()V", at = @At("HEAD"), cancellable = true)
    private void recast$bossBars(CallbackInfo callback) {
        if (RecastHooks.hideHudPart(RecastHooks.HUD_BOSS)) {
            callback.cancel();
        }
    }

    @Inject(method = "renderScoreboardObjective(Lnet/minecraft/scoreboard/ScoreboardObjective;Lnet/minecraft/client/render/Window;)V", at = @At("HEAD"), cancellable = true)
    private void recast$scoreboard(ScoreboardObjective objective, Window window, CallbackInfo callback) {
        if (RecastHooks.hideHudPart(RecastHooks.HUD_SCOREBOARD)) {
            callback.cancel();
        }
    }

    @Inject(method = "renderVignette(FLnet/minecraft/client/render/Window;)V", at = @At("HEAD"), cancellable = true)
    private void recast$vignette(float brightness, Window window, CallbackInfo callback) {
        if (RecastHooks.hideHudPart(RecastHooks.HUD_VIGNETTE)) {
            callback.cancel();
        }
    }
}
