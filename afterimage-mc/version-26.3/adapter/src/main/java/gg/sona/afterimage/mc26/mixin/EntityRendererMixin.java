package gg.sona.afterimage.mc26.mixin;

import gg.sona.afterimage.mc26.AfterimageHooks26;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EntityRenderer.class)
public abstract class EntityRendererMixin<T extends Entity, S extends EntityRenderState> {
    @Inject(method = "extractRenderState", at = @At("HEAD"))
    private void afterimage$beforeExtract(T entity, S state, float partialTicks, CallbackInfo callback) {
        AfterimageHooks26.beforeEntityExtract(entity);
    }

    @Inject(method = "extractRenderState", at = @At("RETURN"))
    private void afterimage$afterExtract(T entity, S state, float partialTicks, CallbackInfo callback) {
        Vec3 smoothed = AfterimageHooks26.smoothedPosition(entity);
        if (smoothed != null) {
            state.x = smoothed.x;
            state.y = smoothed.y;
            state.z = smoothed.z;
        }
        if (state.nameTag != null && AfterimageHooks26.hideNametags(entity)) {
            state.nameTag = null;
        }
    }
}
