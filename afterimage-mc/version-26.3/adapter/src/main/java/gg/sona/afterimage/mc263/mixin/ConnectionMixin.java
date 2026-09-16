package gg.sona.afterimage.mc263.mixin;

import gg.sona.afterimage.mc263.replay.ReplayConnections;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.Connection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Connection.class)
public abstract class ConnectionMixin {
    @Unique
    private static final Logger AFTERIMAGE_LOGGER = LoggerFactory.getLogger("Afterimage");

    @Inject(method = "exceptionCaught", at = @At("HEAD"), cancellable = true)
    private void afterimage$keepReplayAlive(ChannelHandlerContext context, Throwable cause, CallbackInfo callback) {
        if (ReplayConnections.isReplay((Connection) (Object) this)) {
            AFTERIMAGE_LOGGER.warn("Afterimage replay packet failed", cause);
            callback.cancel();
        }
    }

    @Inject(method = "channelInactive", at = @At("HEAD"), cancellable = true)
    private void afterimage$ignoreReplayInactive(ChannelHandlerContext context, CallbackInfo callback) {
        if (ReplayConnections.isReplay((Connection) (Object) this)) callback.cancel();
    }
}
