package gg.sona.afterimage.mc189.mixin;

import net.minecraft.client.gui.screen.inventory.BookEditScreen;
import net.minecraft.nbt.NbtList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(BookEditScreen.class)
public interface BookEditScreenAccessor {
    @Accessor("currentPage")
    int afterimage$currentPage();

    @Accessor("currentPage")
    void afterimage$setCurrentPage(int page);

    @Accessor("pagesNbt")
    NbtList afterimage$pages();

    @Accessor("signing")
    boolean afterimage$signing();

    @Accessor("unsigned")
    boolean afterimage$unsigned();

    @Accessor("title")
    String afterimage$title();

    @Accessor("title")
    void afterimage$setTitle(String title);

    @Accessor("verticalMargin")
    int afterimage$pageCount();

    @Accessor("verticalMargin")
    void afterimage$setPageCount(int count);

    @Accessor("signing")
    void afterimage$setSigning(boolean signing);
}
