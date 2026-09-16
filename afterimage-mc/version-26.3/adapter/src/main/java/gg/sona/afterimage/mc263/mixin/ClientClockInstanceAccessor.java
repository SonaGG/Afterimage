package gg.sona.afterimage.mc263.mixin;

import net.minecraft.client.ClientClockManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ClientClockManager.ClientClockInstance.class)
public interface ClientClockInstanceAccessor {
    @Accessor("totalTicks")
    long afterimage_totalTicks();

    @Accessor("totalTicks")
    void afterimage_setTotalTicks(long value);
}
