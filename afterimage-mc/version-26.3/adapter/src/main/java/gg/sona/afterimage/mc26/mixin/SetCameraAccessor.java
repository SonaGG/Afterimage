package gg.sona.afterimage.mc26.mixin;

import net.minecraft.network.protocol.game.ClientboundSetCameraPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ClientboundSetCameraPacket.class)
public interface SetCameraAccessor {
    @Accessor("cameraId")
    int afterimage_cameraId();
}
