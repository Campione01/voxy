package me.cortex.voxy.client.mixin.iris;

import net.irisshaders.iris.gl.IrisRenderSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = IrisRenderSystem.class, remap = false)
public interface IrisRenderSystemAccessor {
    @Accessor("samplers")
    static int[] voxy$getSamplerBindings() {
        throw new AssertionError();
    }
}
