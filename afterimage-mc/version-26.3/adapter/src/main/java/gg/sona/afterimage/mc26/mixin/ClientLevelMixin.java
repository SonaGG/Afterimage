package gg.sona.afterimage.mc26.mixin;

import gg.sona.afterimage.mc26.AfterimageHooks26;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.prediction.BlockStatePredictionHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ClientLevel.class)
public abstract class ClientLevelMixin {
    @Shadow
    abstract BlockStatePredictionHandler getBlockStatePredictionHandler();

    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z", at = @At("RETURN"))
    private void afterimage$onSetBlock(BlockPos pos, BlockState state, int updateFlags, int updateLimit, CallbackInfoReturnable<Boolean> callback) {
        if (callback.getReturnValueZ() && getBlockStatePredictionHandler().isPredicting()) {
            if ((Object) this == Minecraft.getInstance().level) AfterimageHooks26.onLocalBlockChange(pos, state);
        }
    }

    @Redirect(method = "doAddParticle", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;position()Lnet/minecraft/world/phys/Vec3;"))
    private Vec3 afterimage$particleOrigin(Camera camera) {
        return AfterimageHooks26.particleOrigin(camera);
    }

    @Redirect(method = "animateTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/RandomSource;createThreadLocalInstance()Lnet/minecraft/util/RandomSource;"))
    private RandomSource afterimage$animateRandom() {
        return AfterimageHooks26.animateRandom();
    }

    @Inject(method = "destroyBlockProgress", at = @At("HEAD"))
    private void afterimage$onDestroyProgress(int id, BlockPos pos, int progress, CallbackInfo callback) {
        AfterimageHooks26.onBlockMiningProgress(id, pos, progress);
    }
}
