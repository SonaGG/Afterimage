package gg.sona.afterimage.mc189.mixin;

import gg.sona.afterimage.mc189.AfterimageHooks;
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
    private void afterimage$onCompressionChanged(int threshold, CallbackInfo callback) {
        if (channel != null) {
            AfterimageHooks.onCompressionChanged(channel);
        }
    }

    @Inject(method = "exceptionCaught", at = @At("HEAD"), cancellable = true)
    private void afterimage$keepReplayAlive(ChannelHandlerContext context, Throwable throwable, CallbackInfo callback) {
        if (AfterimageHooks.onReplayConnectionError((Connection) (Object) this, throwable)) {
            callback.cancel();
        }
    }
}
