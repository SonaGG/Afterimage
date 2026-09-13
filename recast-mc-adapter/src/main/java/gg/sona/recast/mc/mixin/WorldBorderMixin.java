package gg.sona.recast.mc.mixin;

import gg.sona.recast.mc.RecastHooks;
import net.minecraft.world.border.WorldBorder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(WorldBorder.class)
public abstract class WorldBorderMixin {
    @Redirect(method = {"getLerpSize()D", "getLerpTime()J", "setSize(D)V", "setSize(DDJ)V"}, at = @At(value = "INVOKE", target = "Ljava/lang/System;currentTimeMillis()J"))
    private long recast$borderTime() {
        return RecastHooks.borderTime();
    }
}
