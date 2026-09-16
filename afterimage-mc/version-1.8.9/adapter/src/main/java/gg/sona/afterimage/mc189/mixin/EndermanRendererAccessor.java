package gg.sona.afterimage.mc189.mixin;

import net.minecraft.client.render.entity.EndermanRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Random;

@Mixin(EndermanRenderer.class)
public interface EndermanRendererAccessor {
    @Accessor("random")
    Random afterimage$random();
}
