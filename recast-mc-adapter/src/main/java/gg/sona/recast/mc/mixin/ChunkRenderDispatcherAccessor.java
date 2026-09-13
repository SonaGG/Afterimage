package gg.sona.recast.mc.mixin;

import net.minecraft.client.render.world.ChunkBufferBuilders;
import net.minecraft.client.render.world.ChunkCompileTask;
import net.minecraft.client.render.world.ChunkRenderDispatcher;
import net.minecraft.client.render.world.ChunkRenderWorker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;
import java.util.Queue;
import java.util.concurrent.BlockingQueue;

@Mixin(ChunkRenderDispatcher.class)
public interface ChunkRenderDispatcherAccessor {
    @Accessor("pendingTasks")
    BlockingQueue<ChunkCompileTask> recast$pendingTasks();

    @Accessor("availableBuffers")
    BlockingQueue<ChunkBufferBuilders> recast$availableBuffers();

    @Accessor("pendingUploads")
    Queue<?> recast$pendingUploads();

    @Accessor("workers")
    List<ChunkRenderWorker> recast$workers();
}
