package gg.sona.afterimage.mc26.mixin;

import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ChatComponent.class)
public interface ChatComponentInvoker {
    @Invoker("addMessageToDisplayQueue")
    void afterimage_addToDisplayQueue(GuiMessage message);

    @Invoker("addMessageToQueue")
    void afterimage_addToQueue(GuiMessage message);
}
