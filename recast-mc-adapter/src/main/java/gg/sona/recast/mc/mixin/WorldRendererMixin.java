package gg.sona.recast.mc.mixin;

import gg.sona.recast.mc.RecastHooks;
import net.minecraft.client.render.world.WorldRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(WorldRenderer.class)
public abstract class WorldRendererMixin {
    @Shadow
    private int ticks;

    @Inject(method = "renderClouds(FI)V", at = @At("HEAD"))
    private void recast$replayCloudTicks(float tickDelta, int pass, CallbackInfo callback) {
        int replayTicks = RecastHooks.cloudTicks(tickDelta);
        if (replayTicks != Integer.MIN_VALUE) {
            ticks = replayTicks;
        }
    }

    @Inject(method = "tick()V", at = @At("HEAD"), cancellable = true)
    private void recast$freezeWorldRendererTick(CallbackInfo callback) {
        if (RecastHooks.worldFrozen()) {
            callback.cancel();
        }
    }

    @Redirect(method = "renderWorldBorder(Lnet/minecraft/entity/Entity;F)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;getTime()J"))
    private long recast$borderTime() {
        return RecastHooks.animationTime();
    }

    @Inject(method = "updateBlockMiningProgress(ILnet/minecraft/util/math/BlockPos;I)V", at = @At("HEAD"))
    private void recast$onMiningProgress(int breakerId, net.minecraft.util.math.BlockPos pos, int progress, CallbackInfo callback) {
        RecastHooks.onBlockMiningProgress(breakerId, pos, progress);
    }

    @Inject(method = "renderSky(FI)V", at = @At("HEAD"), cancellable = true)
    private void recast$hideSky(float tickDelta, int pass, CallbackInfo callback) {
        if (RecastHooks.hideSky()) {
            callback.cancel();
        }
    }
}
