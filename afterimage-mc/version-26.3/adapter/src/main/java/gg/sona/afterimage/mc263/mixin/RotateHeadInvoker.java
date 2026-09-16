package gg.sona.afterimage.mc263.mixin;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ClientboundRotateHeadPacket.class)
public interface RotateHeadInvoker {
    @Invoker("<init>")
    static ClientboundRotateHeadPacket afterimage_read(FriendlyByteBuf input) {
        throw new AssertionError();
    }
}
