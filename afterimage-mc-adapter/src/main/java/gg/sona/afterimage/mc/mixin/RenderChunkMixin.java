package gg.sona.afterimage.mc.mixin;

import net.minecraft.client.render.world.RenderChunk;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(RenderChunk.class)
public abstract class RenderChunkMixin {
    @Inject(method = "setOrigin(Lnet/minecraft/util/math/BlockPos;)V", at = @At("RETURN"))
    private void afterimage$rebuildAfterMove(BlockPos origin, CallbackInfo callback) {
        ((RenderChunk) (Object) this).setDirty(true);
    }
}
