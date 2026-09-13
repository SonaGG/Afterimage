package gg.sona.recast.mc.mixin;

import net.minecraft.client.gui.screen.inventory.menu.AnvilScreen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(AnvilScreen.class)
public interface AnvilScreenAccessor {
    @Accessor("renameTextField")
    TextFieldWidget recast$renameField();
}
