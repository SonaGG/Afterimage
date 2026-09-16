package gg.sona.afterimage.mc263.mixin;

import java.util.List;
import net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ClientboundUpdateAttributesPacket.class)
public interface UpdateAttributesInvoker {
    @Invoker("<init>")
    static ClientboundUpdateAttributesPacket afterimage_create(int entityId, List<ClientboundUpdateAttributesPacket.AttributeSnapshot> attributes) {
        throw new AssertionError();
    }
}
