package me.cortex.voxy.client.mixin.sodium;

import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.client.core.util.IrisUtil;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.SectionCollector;
import net.minecraft.client.Minecraft;
import net.minecraft.core.SectionPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = SectionCollector.class, remap = false)
public class MixinSectionCollector {
    @Inject(method = "visit(Lnet/caffeinemc/mods/sodium/client/render/chunk/RenderSection;I)V", at = @At("HEAD"))
    private void voxy$collectVisibleSection(RenderSection section, int flags, CallbackInfo ci) {
        var levelRenderer = Minecraft.getInstance().levelRenderer;
        if (levelRenderer == null || IrisUtil.irisShadowActive() || !section.isBuilt()) {
            return;
        }

        var renderSystem = ((IGetVoxyRenderSystem) levelRenderer).voxy$getRenderSystem();
        if (renderSystem != null) {
            renderSystem.visbleSectionStream.put(SectionPos.asLong(
                    section.getChunkX(),
                    section.getChunkY(),
                    section.getChunkZ()));
        }
    }
}
