package gg.sona.afterimage.mc263.mixin;

import gg.sona.afterimage.mc263.AfterimageHooks;
import net.minecraft.client.renderer.SectionOcclusionGraph;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SectionOcclusionGraph.class)
public abstract class SectionOcclusionGraphMixin {
    @Inject(method = "schedulePropagationFrom", at = @At("HEAD"))
    private void afterimage$mirrorPropagation(SectionRenderDispatcher.RenderSection section, CallbackInfo callback) {
        AfterimageHooks.onPropagationScheduled((SectionOcclusionGraph) (Object) this, section);
    }
}
