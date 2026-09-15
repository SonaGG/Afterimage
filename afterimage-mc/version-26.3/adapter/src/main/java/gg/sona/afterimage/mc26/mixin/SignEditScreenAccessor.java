package gg.sona.afterimage.mc26.mixin;

import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(AbstractSignEditScreen.class)
public interface SignEditScreenAccessor {
    @Accessor("sign")
    SignBlockEntity afterimage_sign();

    @Accessor("messages")
    String[] afterimage_messages();

    @Accessor("line")
    int afterimage_line();

    @Accessor("line")
    void afterimage_setLine(int line);
}
