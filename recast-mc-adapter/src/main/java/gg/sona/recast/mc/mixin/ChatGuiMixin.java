package gg.sona.recast.mc.mixin;

import gg.sona.recast.mc.RecastHooks;
import net.minecraft.client.gui.chat.ChatGui;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ChatGui.class)
public abstract class ChatGuiMixin {
    @Inject(method = "render(I)V", at = @At("HEAD"), cancellable = true)
    private void recast$chat(int ticks, CallbackInfo callback) {
        if (RecastHooks.hideHudPart(RecastHooks.HUD_CHAT)) {
            callback.cancel();
        }
    }

    @Inject(method = "isChatFocused()Z", at = @At("HEAD"), cancellable = true)
    private void recast$mirroredFocus(CallbackInfoReturnable<Boolean> callback) {
        if (RecastHooks.chatFocusMirrored()) {
            callback.setReturnValue(true);
        }
    }
}
