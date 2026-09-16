package gg.sona.afterimage.mc189.mixin;

import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.client.gui.screen.inventory.menu.SignEditScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(SignEditScreen.class)
public interface SignEditScreenAccessor {
    @Accessor("sign")
    SignBlockEntity afterimage$sign();

    @Accessor("row")
    int afterimage$row();

    @Accessor("row")
    void afterimage$setRow(int row);
}
