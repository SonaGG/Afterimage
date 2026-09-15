package gg.sona.afterimage.mc26.mixin;

import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.level.GameType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(MultiPlayerGameMode.class)
public interface MultiPlayerGameModeAccessor {
    @Accessor("localPlayerMode")
    GameType afterimage_localPlayerMode();

    @Accessor("localPlayerMode")
    void afterimage_setLocalPlayerMode(GameType mode);
}
