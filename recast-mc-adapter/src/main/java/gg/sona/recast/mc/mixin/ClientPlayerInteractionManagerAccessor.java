package gg.sona.recast.mc.mixin;

import net.minecraft.client.ClientPlayerInteractionManager;
import net.minecraft.world.WorldSettings;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ClientPlayerInteractionManager.class)
public interface ClientPlayerInteractionManagerAccessor {
    @Accessor("gameMode")
    void recast$setRawGameMode(WorldSettings.GameMode mode);
}
