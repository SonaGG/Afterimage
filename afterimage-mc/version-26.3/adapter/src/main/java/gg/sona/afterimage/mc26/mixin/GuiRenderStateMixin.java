package gg.sona.afterimage.mc26.mixin;

import gg.sona.afterimage.mc26.AfterimageHooks26;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GuiRenderState.class)
public abstract class GuiRenderStateMixin {
    @Inject(method = "blurBeforeThisStratum", at = @At("HEAD"), cancellable = true)
    private void afterimage$skipMirrorBlur(CallbackInfo callback) {
        if (AfterimageHooks26.suppressMenuBlur()) {
            callback.cancel();
        }
    }
}
