package gg.sona.afterimage.mc26.mixin;

import net.minecraft.client.gui.Font;
import net.minecraft.util.RandomSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Font.class)
public interface FontAccessor {
    @Accessor("random")
    RandomSource afterimage_random();
}
