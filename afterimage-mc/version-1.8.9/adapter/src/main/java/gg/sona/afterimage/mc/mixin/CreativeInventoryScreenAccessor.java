package gg.sona.afterimage.mc.mixin;

import net.minecraft.client.gui.screen.inventory.menu.CreativeInventoryListener;
import net.minecraft.client.gui.screen.inventory.menu.CreativeInventoryScreen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.item.CreativeModeTab;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(CreativeInventoryScreen.class)
public interface CreativeInventoryScreenAccessor {
    @Accessor("searchField")
    TextFieldWidget afterimage$searchField();

    @Accessor("scrollPosition")
    float afterimage$scrollPosition();

    @Accessor("scrollPosition")
    void afterimage$setScrollPosition(float value);

    @Accessor("listener")
    CreativeInventoryListener afterimage$listener();

    @Invoker("setSelectedTab")
    void afterimage$selectTab(CreativeModeTab tab);

    @Invoker("search")
    void afterimage$search();
}
