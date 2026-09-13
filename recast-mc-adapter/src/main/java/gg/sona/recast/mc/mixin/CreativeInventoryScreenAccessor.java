package gg.sona.recast.mc.mixin;

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
    TextFieldWidget recast$searchField();

    @Accessor("scrollPosition")
    float recast$scrollPosition();

    @Accessor("scrollPosition")
    void recast$setScrollPosition(float value);

    @Accessor("listener")
    CreativeInventoryListener recast$listener();

    @Invoker("setSelectedTab")
    void recast$selectTab(CreativeModeTab tab);

    @Invoker("search")
    void recast$search();
}
