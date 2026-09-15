package gg.sona.afterimage.mc.mixin;

import gg.sona.afterimage.mc.AfterimageHooks;
import net.minecraft.client.world.chunk.ClientChunkCache;
import net.minecraft.util.Long2ObjectHashMap;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.WorldChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

@Mixin(ClientChunkCache.class)
public abstract class ClientChunkCacheMixin {
    @Shadow
    private Long2ObjectHashMap<WorldChunk> chunksByPos;

    @Shadow
    private List<WorldChunk> chunks;

    @Shadow
    private WorldChunk empty;

    @Unique
    private WorldChunk afterimage$replaced;

    @Inject(method = "loadChunk(II)Lnet/minecraft/world/chunk/WorldChunk;", at = @At("HEAD"))
    private void afterimage$rememberReplaced(int x, int z, CallbackInfoReturnable<WorldChunk> callback) {
        afterimage$replaced = chunksByPos.get(ChunkPos.toLong(x, z));
    }

    @Inject(method = "loadChunk(II)Lnet/minecraft/world/chunk/WorldChunk;", at = @At("RETURN"))
    private void afterimage$migrateEntities(int x, int z, CallbackInfoReturnable<WorldChunk> callback) {
        WorldChunk previous = afterimage$replaced;
        afterimage$replaced = null;
        WorldChunk next = callback.getReturnValue();
        if (next == null) {
            return;
        }
        if (previous != null && previous != next) {
            chunks.remove(previous);
            previous.setLoaded(false);
        }
        AfterimageHooks.onChunkLoaded(previous != next ? previous : null, empty, next);
    }
}
