package gg.sona.afterimage.mc263.mixin;

import net.minecraft.client.renderer.texture.SpriteContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(SpriteContents.AnimationState.class)
public interface AnimationStateAccessor {
    @Accessor("frame")
    int afterimage_frame();

    @Accessor("frame")
    void afterimage_setFrame(int frame);

    @Accessor("subFrame")
    void afterimage_setSubFrame(int subFrame);

    @Accessor("isDirty")
    void afterimage_setDirty(boolean dirty);

    @Accessor("animationInfo")
    SpriteContents.AnimatedTexture afterimage_animationInfo();
}
