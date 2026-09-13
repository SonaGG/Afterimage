package gg.sona.recast.mc.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.TickTimer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Minecraft.class)
public interface MinecraftAccessor {
    @Accessor("timer")
    TickTimer recast$timer();
}
