package gg.sona.afterimage.mc263.mixin;

import net.minecraft.client.gui.Hud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Hud.class)
public interface HudAccessor {
    @Accessor("overlayMessageTime")
    void afterimage_setOverlayMessageTime(int value);

    @Accessor("isHidden")
    void afterimage_setHidden(boolean hidden);

    @Accessor("random")
    net.minecraft.util.RandomSource afterimage_random();

    @Accessor("titleTime")
    int afterimage_titleTime();

    @Accessor("titleTime")
    void afterimage_setTitleTime(int value);

    @Accessor("titleFadeInTime")
    int afterimage_titleFadeInTime();

    @Accessor("titleStayTime")
    int afterimage_titleStayTime();

    @Accessor("titleFadeOutTime")
    int afterimage_titleFadeOutTime();
}
