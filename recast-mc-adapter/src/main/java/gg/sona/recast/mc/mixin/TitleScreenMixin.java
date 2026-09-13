package gg.sona.recast.mc.mixin;

import gg.sona.recast.mc.RecastHooks;
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
    private void recast$addReplaysButton(CallbackInfo callback) {
        this.buttons.add(new ButtonWidget(RecastHooks.REPLAYS_BUTTON_ID, this.width / 2 - 100, this.height / 4 + 48 + 72 + 12 + 24, "Recast Replays"));
    }

    @Inject(method = "buttonClicked", at = @At("HEAD"))
    private void recast$openReplays(ButtonWidget button, CallbackInfo callback) {
        if (button.id == RecastHooks.REPLAYS_BUTTON_ID) {
            RecastHooks.openWorkspace();
        }
    }
}
