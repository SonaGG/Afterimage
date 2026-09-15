package gg.sona.afterimage.mc.mixin;

import net.minecraft.client.gui.GameGui;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(GameGui.class)
public interface GameGuiAccessor {
    @Accessor("ticks")
    int afterimage$ticks();

    @Accessor("titleTime")
    int afterimage$titleTime();

    @Accessor("titleTime")
    void afterimage$setTitleTime(int value);

    @Accessor("titleFadeInTime")
    int afterimage$titleFadeInTime();

    @Accessor("titleDuration")
    int afterimage$titleDuration();

    @Accessor("titleFadeOutTime")
    int afterimage$titleFadeOutTime();

    @Accessor("overlayMessageCooldown")
    int afterimage$overlayMessageCooldown();

    @Accessor("overlayMessageCooldown")
    void afterimage$setOverlayMessageCooldown(int value);

    @Accessor("random")
    java.util.Random afterimage$random();
}
