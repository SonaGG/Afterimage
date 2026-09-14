package gg.sona.afterimage.mc.mixin;

import gg.sona.afterimage.mc.AfterimageHooks;
import net.minecraft.block.state.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(World.class)
public abstract class WorldBlockMixin {
    @Inject(method = "setBlockState(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/state/BlockState;I)Z", at = @At("RETURN"))
    private void afterimage$onBlockChanged(BlockPos pos, BlockState state, int flags, CallbackInfoReturnable<Boolean> callback) {
        if (callback.getReturnValueZ()) {
            AfterimageHooks.onLocalBlockChange((World) (Object) this, pos, state);
        }
    }
}
