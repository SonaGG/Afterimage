package gg.sona.afterimage.mc26.mixin;

import gg.sona.afterimage.mc26.AfterimageHooks26;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TitleScreen.class)
public abstract class TitleScreenMixin extends Screen {
    protected TitleScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("RETURN"))
    private void afterimage$addReplaysButton(CallbackInfo callback) {
        int y = this.height / 4 + 48 + 24 * 3 + 24 + 24;
        this.addRenderableWidget(Button.builder(Component.literal("Afterimage Replays"), button -> AfterimageHooks26.openWorkspace()).bounds(this.width / 2 - 100, y, 200, 20).build());
    }
}
