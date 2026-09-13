package gg.sona.recast.mc.mixin;

import net.minecraft.client.gui.screen.inventory.BookEditScreen;
import net.minecraft.nbt.NbtList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(BookEditScreen.class)
public interface BookEditScreenAccessor {
    @Accessor("currentPage")
    int recast$currentPage();

    @Accessor("currentPage")
    void recast$setCurrentPage(int page);

    @Accessor("pagesNbt")
    NbtList recast$pages();

    @Accessor("signing")
    boolean recast$signing();

    @Accessor("title")
    String recast$title();

    @Accessor("title")
    void recast$setTitle(String title);

    @Accessor("verticalMargin")
    int recast$pageCount();

    @Accessor("verticalMargin")
    void recast$setPageCount(int count);

    @Accessor("signing")
    void recast$setSigning(boolean signing);
}
