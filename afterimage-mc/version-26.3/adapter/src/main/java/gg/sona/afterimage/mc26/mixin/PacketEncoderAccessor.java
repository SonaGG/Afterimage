package gg.sona.afterimage.mc26.mixin;

import net.minecraft.network.PacketEncoder;
import net.minecraft.network.ProtocolInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(PacketEncoder.class)
public interface PacketEncoderAccessor {
    @Accessor("protocolInfo")
    ProtocolInfo<?> afterimage_protocolInfo();
}
