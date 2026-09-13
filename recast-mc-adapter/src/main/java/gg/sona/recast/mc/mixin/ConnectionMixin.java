package gg.sona.recast.mc.mixin;

import gg.sona.recast.mc.RecastHooks;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.Connection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Connection.class)
public abstract class ConnectionMixin {
    @Shadow
    public Channel channel;

    @Inject(method = "setCompressionThreshold", at = @At("RETURN"))
    private void recast$onCompressionChanged(int threshold, CallbackInfo callback) {
        if (channel != null) {
            RecastHooks.onCompressionChanged(channel);
        }
    }

    @Inject(method = "exceptionCaught", at = @At("HEAD"), cancellable = true)
    private void recast$keepReplayAlive(ChannelHandlerContext context, Throwable throwable, CallbackInfo callback) {
        if (RecastHooks.onReplayConnectionError((Connection) (Object) this, throwable)) {
            callback.cancel();
        }
    }
}
