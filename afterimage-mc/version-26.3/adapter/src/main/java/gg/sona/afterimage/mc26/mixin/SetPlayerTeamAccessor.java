package gg.sona.afterimage.mc26.mixin;

import net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ClientboundSetPlayerTeamPacket.class)
public interface SetPlayerTeamAccessor {
    @Accessor("method")
    int afterimage_method();
}
