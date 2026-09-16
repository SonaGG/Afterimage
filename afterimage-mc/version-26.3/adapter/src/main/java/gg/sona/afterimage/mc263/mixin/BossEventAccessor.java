package gg.sona.afterimage.mc263.mixin;

import java.util.UUID;
import net.minecraft.network.protocol.game.ClientboundBossEventPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ClientboundBossEventPacket.class)
public interface BossEventAccessor {
    @Accessor("id")
    UUID afterimage_id();
}
