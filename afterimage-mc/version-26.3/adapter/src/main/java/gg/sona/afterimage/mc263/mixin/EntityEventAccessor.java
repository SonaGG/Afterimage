package gg.sona.afterimage.mc263.mixin;

import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ClientboundEntityEventPacket.class)
public interface EntityEventAccessor {
    @Accessor("entityId")
    int afterimage_entityId();
}
