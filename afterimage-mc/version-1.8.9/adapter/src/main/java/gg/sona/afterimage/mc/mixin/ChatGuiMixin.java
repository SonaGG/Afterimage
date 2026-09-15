package gg.sona.afterimage.mc.mixin;

import gg.sona.afterimage.mc.AfterimageHooks;
import net.minecraft.client.gui.chat.ChatGui;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ChatGui.class)
public abstract class ChatGuiMixin {
    @Inject(method = "render(I)V", at = @At("HEAD"), cancellable = true)
    private void afterimage$chat(int ticks, CallbackInfo callback) {
        if (AfterimageHooks.hideHudPart(AfterimageHooks.HUD_CHAT)) {
            callback.cancel();
        }
    }

    @Inject(method = "isChatFocused()Z", at = @At("HEAD"), cancellable = true)
    private void afterimage$mirroredFocus(CallbackInfoReturnable<Boolean> callback) {
        if (AfterimageHooks.chatFocusMirrored()) {
            callback.setReturnValue(true);
        }
    }
}
