package gg.sona.afterimage.mc26.mixin;

import gg.sona.afterimage.mc26.AfterimageHooks26;
import net.minecraft.client.renderer.item.properties.numeric.Time;
import net.minecraft.util.RandomSource;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Time.class)
public abstract class TimePropertyMixin {
    @Shadow
    @Final
    private RandomSource randomSource;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void afterimage$registerRandom(CallbackInfo callback) {
        AfterimageHooks26.registerVisualRandom(this.randomSource);
    }
}
