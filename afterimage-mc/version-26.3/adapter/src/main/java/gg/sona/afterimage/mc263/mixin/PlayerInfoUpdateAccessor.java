package gg.sona.afterimage.mc263.mixin;

import java.util.List;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ClientboundPlayerInfoUpdatePacket.class)
public interface PlayerInfoUpdateAccessor {
    @Accessor("entries")
    @Mutable
    void afterimage_setEntries(List<ClientboundPlayerInfoUpdatePacket.Entry> entries);
}
