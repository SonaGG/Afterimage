package gg.sona.recast.mc.mixin;

import net.minecraft.client.gui.screen.inventory.menu.VillagerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(VillagerScreen.class)
public interface VillagerScreenAccessor {
    @Accessor("currentPage")
    int recast$currentPage();

    @Accessor("currentPage")
    void recast$setCurrentPage(int page);
}
