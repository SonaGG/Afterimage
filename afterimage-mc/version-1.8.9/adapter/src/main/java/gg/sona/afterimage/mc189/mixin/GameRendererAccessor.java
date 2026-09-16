package gg.sona.afterimage.mc189.mixin;

import net.minecraft.client.render.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Random;

@Mixin(GameRenderer.class)
public interface GameRendererAccessor {
    @Accessor("random")
    Random afterimage$random();
}
