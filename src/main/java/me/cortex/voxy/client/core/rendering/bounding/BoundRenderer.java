package me.cortex.voxy.client.core.rendering.bounding;

import me.cortex.voxy.client.core.AbstractRenderPipeline;
import me.cortex.voxy.client.core.RenderProperties;
import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.gl.GlVertexArray;
import me.cortex.voxy.client.core.gl.shader.AutoBindingShader;
import me.cortex.voxy.client.core.gl.shader.Shader;
import me.cortex.voxy.client.core.gl.shader.ShaderLoader;
import me.cortex.voxy.client.core.gl.shader.ShaderType;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.rendering.util.SharedIndexBuffer;
import me.cortex.voxy.client.core.rendering.util.UploadStream;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3i;
import org.lwjgl.system.MemoryUtil;

import static org.lwjgl.opengl.GL11.GL_TRIANGLES;
import static org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE;
import static org.lwjgl.opengl.GL15.GL_ELEMENT_ARRAY_BUFFER;
import static org.lwjgl.opengl.GL15.glBindBuffer;
import static org.lwjgl.opengl.GL30.glBindVertexArray;
import static org.lwjgl.opengl.GL30C.GL_CCW;
import static org.lwjgl.opengl.GL30C.GL_CULL_FACE;
import static org.lwjgl.opengl.GL30C.GL_CW;
import static org.lwjgl.opengl.GL30C.GL_DEPTH_TEST;
import static org.lwjgl.opengl.GL30C.glDepthFunc;
import static org.lwjgl.opengl.GL30C.glEnable;
import static org.lwjgl.opengl.GL30C.glFrontFace;
import static org.lwjgl.opengl.GL31.glDrawElementsInstanced;
import static org.lwjgl.opengl.GL42.glDrawElementsInstancedBaseInstance;

public final class BoundRenderer {
    private final GlBuffer uniformBuffer = new GlBuffer(128);
    private final Shader rasterShader;
    private final RenderProperties properties;
    private final AbstractRenderPipeline pipeline;

    public BoundRenderer(AbstractRenderPipeline pipeline) {
        this.properties = pipeline.properties;

        String vertexSource = ShaderLoader.parse("voxy:chunkoutline/outline.vsh");
        String taa = pipeline.taaFunction("getTAA");
        if (taa != null) {
            this.pipeline = pipeline;
            vertexSource = vertexSource + "\n\n\n" + taa;
        } else {
            this.pipeline = null;
        }

        this.rasterShader = Shader.makeAuto()
                .addSource(ShaderType.VERTEX, vertexSource)
                .defineIf("TAA", taa != null)
                .add(ShaderType.FRAGMENT, "voxy:chunkoutline/outline.fsh")
                .apply(this.properties::apply)
                .compile()
                .ubo(0, this.uniformBuffer);
    }

    public void render(Viewport<?> viewport, IBoundStore store) {
        store.preRender(viewport);
        int count = store.getCount();
        if (count == 0) {
            viewport.depthBoundingBuffer.clear(this.properties.inverseClearDepth());
            return;
        }

        ((AutoBindingShader) this.rasterShader).ssbo(1, store.getBuffer());
        float renderDistance = Minecraft.getInstance().options.getEffectiveRenderDistance() * 16.0f;
        this.renderInner(viewport, renderDistance, count);
        store.postRender(viewport);
    }

    private void renderInner(Viewport<?> viewport, float renderDistanceBlocks, int sectionCount) {
        viewport.depthBoundingBuffer.clear(this.properties.inverseClearDepth());
        if (sectionCount == 0) {
            return;
        }

        long pointer = UploadStream.INSTANCE.upload(this.uniformBuffer, 0, 128);
        long matrixPointer = pointer;
        pointer += 4 * 4 * 4;

        int blockX = (int) Math.floor(viewport.cameraX);
        int blockY = (int) Math.floor(viewport.cameraY);
        int blockZ = (int) Math.floor(viewport.cameraZ);
        new Vector3i(blockX, blockY, blockZ).getToAddress(pointer);
        pointer += 4 * 4;

        var innerBlock = new Vector3f(
                (float) (viewport.cameraX - blockX),
                (float) (viewport.cameraY - blockY),
                (float) (viewport.cameraZ - blockZ));

        innerBlock.getToAddress(pointer);
        pointer += 4 * 3;
        viewport.MVP.translate(innerBlock.negate(), new Matrix4f()).getToAddress(matrixPointer);
        MemoryUtil.memPutFloat(pointer, renderDistanceBlocks);
        UploadStream.INSTANCE.commit();

        glFrontFace(GL_CW);
        glEnable(GL_CULL_FACE);
        glEnable(GL_DEPTH_TEST);
        glDepthFunc(this.properties.furtherDepthCompare());

        glBindVertexArray(GlVertexArray.STATIC_VAO);
        viewport.depthBoundingBuffer.bind();
        this.rasterShader.bind();
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, SharedIndexBuffer.INSTANCE_BB_BYTE.id());
        if (this.pipeline != null) {
            this.pipeline.bindUniforms();
        }

        if (sectionCount >= 32) {
            glDrawElementsInstanced(GL_TRIANGLES, 6 * 2 * 3 * 32, GL_UNSIGNED_BYTE, 0, sectionCount / 32);
        }
        if (sectionCount % 32 != 0) {
            glDrawElementsInstancedBaseInstance(
                    GL_TRIANGLES,
                    6 * 2 * 3 * (sectionCount % 32),
                    GL_UNSIGNED_BYTE,
                    0,
                    1,
                    (sectionCount / 32) * 32);
        }

        glFrontFace(GL_CCW);
        glDepthFunc(this.properties.closerEqualDepthCompare());
        glEnable(GL_CULL_FACE);
        glEnable(GL_DEPTH_TEST);
    }

    public void free() {
        this.rasterShader.free();
        this.uniformBuffer.free();
    }
}
