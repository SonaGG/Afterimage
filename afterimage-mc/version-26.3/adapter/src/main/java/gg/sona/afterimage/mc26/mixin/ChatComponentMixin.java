package gg.sona.afterimage.mc26.mixin;

import gg.sona.afterimage.mc26.AfterimageHooks26;
import net.minecraft.client.gui.components.ChatComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ChatComponent.class)
public abstract class ChatComponentMixin {
    @Inject(method = "isChatFocused", at = @At("HEAD"), cancellable = true)
    private void afterimage$mirroredFocus(CallbackInfoReturnable<Boolean> callback) {
        if (AfterimageHooks26.chatFocusMirrored()) {
            callback.setReturnValue(true);
        }
    }
}
