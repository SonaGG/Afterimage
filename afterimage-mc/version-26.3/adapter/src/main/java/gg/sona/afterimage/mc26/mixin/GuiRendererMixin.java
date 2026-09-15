package gg.sona.afterimage.mc26.mixin;

import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import gg.sona.afterimage.mc26.AfterimageHooks26;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.renderer.Projection;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(GuiRenderer.class)
public abstract class GuiRendererMixin {
    @Redirect(
            method = "draw",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/ProjectionMatrixBuffer;getBuffer(Lnet/minecraft/client/renderer/Projection;)Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;")
    )
    private GpuBufferSlice afterimage$guiProjection(ProjectionMatrixBuffer buffer, Projection projection) {
        return buffer.getBuffer(AfterimageHooks26.hudProjection(projection.getMatrix(new Matrix4f())));
    }

    @Redirect(
            method = "enableScissor",
            at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/api/commands/RenderPass;enableScissor(IIII)V")
    )
    private void afterimage$guiScissor(RenderPass pass, int x, int y, int width, int height) {
        int[] mapped = AfterimageHooks26.guiScissor(x, y, width, height);
        pass.enableScissor(mapped[0], mapped[1], mapped[2], mapped[3]);
    }
}
