package gg.sona.afterimage.mc189.mixin;

import net.minecraft.client.gui.chat.ChatGui;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ChatGui.class)
public interface ChatGuiAccessor {
    @Invoker("addMessage")
    void afterimage$addMessage(Text text, int id, int tick, boolean onlyTrimmed);
}
