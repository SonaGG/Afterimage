package gg.sona.recast.mc.mixin;

import gg.sona.recast.mc.RecastHooks;
import net.minecraft.client.render.world.ChunkRenderDispatcher;
import net.minecraft.client.render.world.RenderChunk;
import net.minecraft.client.render.world.WorldRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(WorldRenderer.class)
public abstract class WorldRendererChunkMixin {
    @ModifyVariable(method = "compileChunksUntil(J)V", at = @At("HEAD"), argsOnly = true)
    private long recast$extendChunkDeadline(long finishNanos) {
        return RecastHooks.chunkDeadline(finishNanos);
    }

    @Redirect(method = "compileChunksUntil(J)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/world/ChunkRenderDispatcher;rebuildAsync(Lnet/minecraft/client/render/world/RenderChunk;)Z"))
    private boolean recast$rebuild(ChunkRenderDispatcher dispatcher, RenderChunk chunk) {
        return RecastHooks.rebuildChunk(dispatcher, chunk);
    }
}
