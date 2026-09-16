package gg.sona.afterimage.mc263.mixin;

import net.minecraft.client.Camera;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Camera.class)
public interface CameraAccessor {
    @Invoker("setPosition")
    void afterimage_setPosition(Vec3 position);

    @Invoker("setRotation")
    void afterimage_setRotation(float yRot, float xRot);

    @Accessor("rotation")
    Quaternionf afterimage_rotation();

    @Accessor("forwards")
    Vector3f afterimage_forwards();

    @Accessor("up")
    Vector3f afterimage_up();

    @Accessor("left")
    Vector3f afterimage_left();

    @Accessor("matrixPropertiesDirty")
    void afterimage_setMatrixPropertiesDirty(int value);

    @Accessor("xRot")
    void afterimage_setXRot(float value);

    @Accessor("yRot")
    void afterimage_setYRot(float value);

    @Accessor("cullFrustum")
    void afterimage_setCullFrustum(Frustum frustum);

    @Accessor("depthFar")
    float afterimage_depthFar();

    @Accessor("eyeHeight")
    void afterimage_setEyeHeight(float value);

    @Accessor("eyeHeightOld")
    void afterimage_setEyeHeightOld(float value);
}
