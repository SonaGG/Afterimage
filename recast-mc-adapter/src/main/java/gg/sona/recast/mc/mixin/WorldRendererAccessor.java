package gg.sona.recast.mc.mixin;

import net.minecraft.client.render.world.ChunkRenderDispatcher;
import net.minecraft.client.render.world.RenderChunk;
import net.minecraft.client.render.world.WorldRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Set;

@Mixin(WorldRenderer.class)
public interface WorldRendererAccessor {
    @Accessor("dirtyChunks")
    Set<RenderChunk> recast$dirtyChunks();

    @Accessor("chunkRenderDispatcher")
    ChunkRenderDispatcher recast$dispatcher();

    @Accessor("viewChanged")
    boolean recast$viewChanged();
}
