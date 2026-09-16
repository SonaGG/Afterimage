package gg.sona.afterimage.mc263.mixin;

import java.util.List;
import net.minecraft.client.gui.screens.inventory.BookEditScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(BookEditScreen.class)
public interface BookEditScreenAccessor {
    @Accessor("currentPage")
    int afterimage_currentPage();

    @Accessor("currentPage")
    void afterimage_setCurrentPage(int page);

    @Accessor("pages")
    List<String> afterimage_pages();

    @Invoker("updatePageContent")
    void afterimage_updatePageContent();

    @Invoker("updateButtonVisibility")
    void afterimage_updateButtonVisibility();
}
