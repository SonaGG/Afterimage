package gg.sona.recast.mc.mixin;

import net.minecraft.client.gui.GameGui;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(GameGui.class)
public interface GameGuiAccessor {
    @Accessor("ticks")
    int recast$ticks();

    @Accessor("titleTime")
    void recast$setTitleTime(int value);

    @Accessor("overlayMessageCooldown")
    void recast$setOverlayMessageCooldown(int value);

    @Accessor("random")
    java.util.Random recast$random();
}
