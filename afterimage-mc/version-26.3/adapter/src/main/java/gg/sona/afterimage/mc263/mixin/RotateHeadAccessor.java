package gg.sona.afterimage.mc263.mixin;

import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ClientboundRotateHeadPacket.class)
public interface RotateHeadAccessor {
    @Accessor("entityId")
    int afterimage_entityId();
}
