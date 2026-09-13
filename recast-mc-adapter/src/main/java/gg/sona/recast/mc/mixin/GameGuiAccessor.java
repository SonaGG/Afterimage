package gg.sona.recast.mc.mixin;

import net.minecraft.client.gui.GameGui;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(GameGui.class)
public interface GameGuiAccessor {
    @Accessor("ticks")
    int recast$ticks();

    @Accessor("titleTime")
    int recast$titleTime();

    @Accessor("titleTime")
    void recast$setTitleTime(int value);

    @Accessor("titleFadeInTime")
    int recast$titleFadeInTime();

    @Accessor("titleDuration")
    int recast$titleDuration();

    @Accessor("titleFadeOutTime")
    int recast$titleFadeOutTime();

    @Accessor("overlayMessageCooldown")
    int recast$overlayMessageCooldown();

    @Accessor("overlayMessageCooldown")
    void recast$setOverlayMessageCooldown(int value);

    @Accessor("random")
    java.util.Random recast$random();
}
