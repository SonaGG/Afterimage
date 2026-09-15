package gg.sona.afterimage.mc.mixin;

import gg.sona.afterimage.mc.AfterimageHooks;
import net.minecraft.world.border.WorldBorder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(WorldBorder.class)
public abstract class WorldBorderMixin {
    @Redirect(method = {"getLerpSize()D", "getLerpTime()J", "setSize(D)V", "setSize(DDJ)V"}, at = @At(value = "INVOKE", target = "Ljava/lang/System;currentTimeMillis()J"))
    private long afterimage$borderTime() {
        return AfterimageHooks.borderTime();
    }
}
