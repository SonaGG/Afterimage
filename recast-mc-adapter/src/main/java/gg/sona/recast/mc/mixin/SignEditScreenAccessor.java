package gg.sona.recast.mc.mixin;

import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.client.gui.screen.inventory.menu.SignEditScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(SignEditScreen.class)
public interface SignEditScreenAccessor {
    @Accessor("sign")
    SignBlockEntity recast$sign();

    @Accessor("row")
    int recast$row();

    @Accessor("row")
    void recast$setRow(int row);
}
