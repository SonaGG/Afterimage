package gg.sona.afterimage.mc189.mixin;

import gg.sona.afterimage.mc189.AfterimageHooks;
import net.minecraft.client.entity.living.player.ClientPlayerEntity;
import net.minecraft.client.render.ItemInHandRenderer;
import net.minecraft.client.render.entity.EntityRenderDispatcher;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.PlayerRenderer;
import net.minecraft.entity.Entity;
import net.minecraft.resource.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(ItemInHandRenderer.class)
public abstract class ItemInHandRendererMixin {
    @Redirect(
            method = {"renderArms(Lnet/minecraft/client/entity/living/player/ClientPlayerEntity;)V", "renderMap(Lnet/minecraft/client/entity/living/player/ClientPlayerEntity;FFF)V"},
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/entity/living/player/ClientPlayerEntity;getSkinTextureLocation()Lnet/minecraft/resource/Identifier;")
    )
    private Identifier afterimage$handSkin(ClientPlayerEntity player) {
        ClientPlayerEntity target = AfterimageHooks.handTarget();
        return (target != null ? target : player).getSkinTextureLocation();
    }

    @Redirect(
            method = {"renderArms(Lnet/minecraft/client/entity/living/player/ClientPlayerEntity;)V", "renderMap(Lnet/minecraft/client/entity/living/player/ClientPlayerEntity;FFF)V"},
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/entity/EntityRenderDispatcher;getRenderer(Lnet/minecraft/entity/Entity;)Lnet/minecraft/client/render/entity/EntityRenderer;")
    )
    private EntityRenderer afterimage$handRenderer(EntityRenderDispatcher dispatcher, Entity entity) {
        ClientPlayerEntity target = AfterimageHooks.handTarget();
        return dispatcher.getRenderer(target != null ? target : entity);
    }

    @Redirect(
            method = {"renderRightArm(Lnet/minecraft/client/render/entity/PlayerRenderer;)V", "renderMap(Lnet/minecraft/client/entity/living/player/ClientPlayerEntity;FFF)V"},
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/entity/PlayerRenderer;renderRightHand(Lnet/minecraft/client/entity/living/player/ClientPlayerEntity;)V")
    )
    private void afterimage$rightHand(PlayerRenderer renderer, ClientPlayerEntity player) {
        ClientPlayerEntity target = AfterimageHooks.handTarget();
        renderer.renderRightHand(target != null ? target : player);
    }

    @Redirect(
            method = "renderLeftArm(Lnet/minecraft/client/render/entity/PlayerRenderer;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/entity/PlayerRenderer;renderPlayerLeftHandModel(Lnet/minecraft/client/entity/living/player/ClientPlayerEntity;)V")
    )
    private void afterimage$leftHand(PlayerRenderer renderer, ClientPlayerEntity player) {
        ClientPlayerEntity target = AfterimageHooks.handTarget();
        renderer.renderPlayerLeftHandModel(target != null ? target : player);
    }
}
