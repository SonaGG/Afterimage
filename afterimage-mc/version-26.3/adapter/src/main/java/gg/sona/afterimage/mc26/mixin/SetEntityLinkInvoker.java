package gg.sona.afterimage.mc26.mixin;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundSetEntityLinkPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ClientboundSetEntityLinkPacket.class)
public interface SetEntityLinkInvoker {
    @Invoker("<init>")
    static ClientboundSetEntityLinkPacket afterimage_read(FriendlyByteBuf input) {
        throw new AssertionError();
    }
}
