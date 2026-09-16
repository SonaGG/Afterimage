package gg.sona.afterimage.mc263.mixin;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.extract.LevelExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(LevelExtractor.class)
public interface LevelExtractorAccessor {
    @Accessor("level")
    void afterimage_setLevel(ClientLevel level);

    @Accessor("lastViewDistance")
    void afterimage_setLastViewDistance(int distance);

    @Accessor("prevCamRotX")
    void afterimage_setPrevCamRotX(double value);

    @Accessor("prevCamRotY")
    void afterimage_setPrevCamRotY(double value);
}
