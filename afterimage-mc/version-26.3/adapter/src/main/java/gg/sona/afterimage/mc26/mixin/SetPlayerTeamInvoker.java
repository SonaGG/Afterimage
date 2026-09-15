package gg.sona.afterimage.mc26.mixin;

import java.util.Collection;
import java.util.Optional;
import net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ClientboundSetPlayerTeamPacket.class)
public interface SetPlayerTeamInvoker {
    @Invoker("<init>")
    static ClientboundSetPlayerTeamPacket afterimage_create(String name, int method, Optional<ClientboundSetPlayerTeamPacket.Parameters> parameters, Collection<String> players) {
        throw new AssertionError();
    }
}
