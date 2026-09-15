package gg.sona.afterimage.mc26.mixin;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.client.renderer.SectionOcclusionGraph;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(SectionOcclusionGraph.class)
public interface SectionOcclusionGraphAccessor {
    @Accessor("needsFullUpdate")
    boolean afterimage_needsFullUpdate();

    @Accessor("fullUpdateTask")
    Future<?> afterimage_fullUpdateTask();

    @Accessor("needsFrustumUpdate")
    AtomicBoolean afterimage_needsFrustumUpdate();

    @Accessor("loadedChunks")
    LongOpenHashSet afterimage_loadedChunks();

    @Accessor("emptySections")
    LongOpenHashSet afterimage_emptySections();

    @Accessor("currentGraph")
    AtomicReference<?> afterimage_currentGraph();
}
