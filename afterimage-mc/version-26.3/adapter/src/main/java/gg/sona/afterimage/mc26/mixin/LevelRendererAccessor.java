package gg.sona.afterimage.mc26.mixin;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.renderer.CloudRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SectionOcclusionGraph;
import net.minecraft.client.renderer.SkyRenderer;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(LevelRenderer.class)
public interface LevelRendererAccessor {
    @Accessor("sectionRenderDispatcher")
    SectionRenderDispatcher afterimage_sectionRenderDispatcher();

    @Accessor("levelRenderState")
    LevelRenderState afterimage_levelRenderState();

    @Accessor("levelRenderState")
    @Mutable
    void afterimage_setLevelRenderState(LevelRenderState state);

    @Accessor("sectionOcclusionGraph")
    @Mutable
    void afterimage_setSectionOcclusionGraph(SectionOcclusionGraph graph);

    @Accessor("visibleSections")
    @Mutable
    void afterimage_setVisibleSections(ObjectArrayList<SectionRenderDispatcher.RenderSection> sections);

    @Accessor("nearbyVisibleSections")
    @Mutable
    void afterimage_setNearbyVisibleSections(ObjectArrayList<SectionRenderDispatcher.RenderSection> sections);

    @Accessor("cloudRenderer")
    @Mutable
    void afterimage_setCloudRenderer(CloudRenderer clouds);

    @Accessor("skyRenderer")
    SkyRenderer afterimage_skyRenderer();

    @Accessor("skyRenderer")
    void afterimage_setSkyRenderer(SkyRenderer sky);
}
