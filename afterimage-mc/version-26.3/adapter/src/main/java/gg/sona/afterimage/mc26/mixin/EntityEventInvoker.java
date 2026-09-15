package gg.sona.afterimage.mc26.mixin;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ClientboundEntityEventPacket.class)
public interface EntityEventInvoker {
    @Invoker("<init>")
    static ClientboundEntityEventPacket afterimage_read(FriendlyByteBuf input) {
        throw new AssertionError();
    }
}
