package gg.sona.afterimage.mc.mixin;

import net.minecraft.client.gui.screen.inventory.menu.VillagerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(VillagerScreen.class)
public interface VillagerScreenAccessor {
    @Accessor("currentPage")
    int afterimage$currentPage();

    @Accessor("currentPage")
    void afterimage$setCurrentPage(int page);
}
