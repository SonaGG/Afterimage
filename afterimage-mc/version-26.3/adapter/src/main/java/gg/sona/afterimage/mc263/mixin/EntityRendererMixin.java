package gg.sona.afterimage.mc263.mixin;

import gg.sona.afterimage.mc263.AfterimageHooks;
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
        AfterimageHooks.beforeEntityExtract(entity);
    }

    @Inject(method = "extractRenderState", at = @At("RETURN"))
    private void afterimage$afterExtract(T entity, S state, float partialTicks, CallbackInfo callback) {
        Vec3 smoothed = AfterimageHooks.smoothedPosition(entity);
        if (smoothed != null) {
            state.x = smoothed.x;
            state.y = smoothed.y;
            state.z = smoothed.z;
        }
        if (state.nameTag != null && AfterimageHooks.hideNametags(entity)) {
            state.nameTag = null;
        }
    }
}
