package gg.sona.afterimage.mc26.mixin;

import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.world.item.CreativeModeTab;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(CreativeModeInventoryScreen.class)
public interface CreativeModeInventoryScreenAccessor {
    @Accessor("searchBox")
    EditBox afterimage_searchBox();

    @Accessor("scrollOffs")
    float afterimage_scrollOffs();

    @Accessor("scrollOffs")
    void afterimage_setScrollOffs(float value);

    @Accessor("selectedTab")
    static CreativeModeTab afterimage_selectedTab() {
        throw new AssertionError();
    }

    @Invoker("selectTab")
    void afterimage_selectTab(CreativeModeTab tab);

    @Invoker("refreshSearchResults")
    void afterimage_refreshSearchResults();
}
