package me.cortex.voxy.client.mixin.nvidium;

import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "me.cortex.nvidium.RenderPipeline", remap = false)
public class MixinRenderPipeline {
    @Inject(method = "renderFrame", at = @At("RETURN"), remap = false)
    private void voxy$injectRender(Viewport frustum, ChunkRenderMatrices matrices,
                                   double x, double y, double z, CallbackInfo ci) {
        var renderer = ((IGetVoxyRenderSystem) Minecraft.getInstance().levelRenderer).voxy$getRenderSystem();
        if (renderer != null) {
            renderer.renderOpaque(renderer.setupViewport(
                    matrices.projection(), matrices.modelView(), x, y, z));
        }
    }
}
