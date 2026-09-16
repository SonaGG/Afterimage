package gg.sona.afterimage.mc189.mixin;

import net.minecraft.client.render.world.ChunkRenderDispatcher;
import net.minecraft.client.render.world.RenderChunk;
import net.minecraft.client.render.world.WorldRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Set;

@Mixin(WorldRenderer.class)
public interface WorldRendererAccessor {
    @Accessor("dirtyChunks")
    Set<RenderChunk> afterimage$dirtyChunks();

    @Accessor("chunkRenderDispatcher")
    ChunkRenderDispatcher afterimage$dispatcher();

    @Accessor("viewChanged")
    boolean afterimage$viewChanged();
}
