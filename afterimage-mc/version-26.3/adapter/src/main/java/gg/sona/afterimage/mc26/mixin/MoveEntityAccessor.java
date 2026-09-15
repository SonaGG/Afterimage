package gg.sona.afterimage.mc26.mixin;

import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ClientboundMoveEntityPacket.class)
public interface MoveEntityAccessor {
    @Accessor("entityId")
    int afterimage_entityId();
}
