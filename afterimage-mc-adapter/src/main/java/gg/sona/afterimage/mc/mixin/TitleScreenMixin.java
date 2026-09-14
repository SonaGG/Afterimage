package gg.sona.afterimage.mc.mixin;

import gg.sona.afterimage.mc.AfterimageHooks;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TitleScreen.class)
public abstract class TitleScreenMixin extends Screen {
    @Inject(method = "init", at = @At("RETURN"))
    private void afterimage$addReplaysButton(CallbackInfo callback) {
        this.buttons.add(new ButtonWidget(AfterimageHooks.REPLAYS_BUTTON_ID, this.width / 2 - 100, this.height / 4 + 48 + 72 + 12 + 24, "Afterimage"));
    }

    @Inject(method = "buttonClicked", at = @At("HEAD"))
    private void afterimage$openReplays(ButtonWidget button, CallbackInfo callback) {
        if (button.id == AfterimageHooks.REPLAYS_BUTTON_ID) {
            AfterimageHooks.openWorkspace();
        }
    }
}
