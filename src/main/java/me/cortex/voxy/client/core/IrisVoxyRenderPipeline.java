package me.cortex.voxy.client.core;

import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.model.ModelBakerySubsystem;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.rendering.hierachical.AsyncNodeManager;
import me.cortex.voxy.client.core.rendering.hierachical.HierarchicalOcclusionTraverser;
import me.cortex.voxy.client.core.rendering.hierachical.NodeCleaner;
import me.cortex.voxy.client.core.rendering.post.FullscreenBlit;
import me.cortex.voxy.client.core.rendering.section.backend.AbstractSectionRenderer;
import me.cortex.voxy.client.core.rendering.util.DepthFramebuffer;
import me.cortex.voxy.client.core.rendering.util.UploadStream;
import me.cortex.voxy.client.iris.IrisVoxyRenderPipelineData;
import me.cortex.voxy.client.mixin.iris.IrisRenderSystemAccessor;
import me.cortex.voxy.common.Logger;
import net.irisshaders.iris.gl.blending.BlendMode;
import net.irisshaders.iris.gl.blending.BlendModeOverride;
import net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL30;

import java.util.List;
import java.util.function.BooleanSupplier;

import static org.lwjgl.opengl.GL11.GL_DEPTH_BUFFER_BIT;
import static org.lwjgl.opengl.GL30C.*;
import static org.lwjgl.opengl.GL31.GL_UNIFORM_BUFFER;
import static org.lwjgl.opengl.GL45C.*;

public class IrisVoxyRenderPipeline extends AbstractRenderPipeline {
    private static final BlendModeOverride DEFAULT_TRANSLUCENT_BLEND = new BlendModeOverride(
            new BlendMode(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA));
    private static final int UNIFORM_BINDING_POINT = 7;
    private static final int BASE_BUFFER_BINDING_INDEX = IrisVoxyRenderPipelineData.FORWARDED_SSBO_BINDING_BASE;
    private static final int BASE_SAMPLER_BINDING_INDEX = IrisVoxyRenderPipelineData.FORWARDED_SAMPLER_BINDING_BASE;

    private final IrisVoxyRenderPipelineData data;
    private final FullscreenBlit depthBlit;
    public final DepthFramebuffer fbTranslucent = new DepthFramebuffer(this.fb.getFormat());

    private final FullscreenBlit shaderDepthHackFixTransformBlit;

    private final GlBuffer shaderUniforms;

    private int validatedSourceFramebuffer = -1;
    private int validatedViewportWidth = -1;
    private int validatedViewportHeight = -1;
    private int validatedSamplingWidth = -1;
    private int validatedSamplingHeight = -1;
    private int validatedTargetWidth = -1;
    private int validatedTargetHeight = -1;
    private int validatedDepthAttachment = -1;
    private int validatedDepthTexture;
    private boolean warnedUnsupportedTarget;

    public IrisVoxyRenderPipeline(RenderProperties properties, IrisVoxyRenderPipelineData data, AsyncNodeManager nodeManager, NodeCleaner nodeCleaner, HierarchicalOcclusionTraverser traversal, BooleanSupplier frexSupplier) {
        super(properties, nodeManager, nodeCleaner, traversal, frexSupplier, data.shouldDeferTranslucency());
        this.data = data;
        if (this.data.thePipeline != null) {
            throw new IllegalStateException("Pipeline data already bound");
        }

        //Bind the drawbuffers
        var oDT = this.data.opaqueDrawTargets;
        int[] binding = new int[oDT.length];
        for (int i = 0; i < oDT.length; i++) {
            binding[i] = GL30.GL_COLOR_ATTACHMENT0+i;
            glNamedFramebufferTexture(this.fb.framebuffer.id, GL30.GL_COLOR_ATTACHMENT0+i, oDT[i], 0);
        }
        glNamedFramebufferDrawBuffers(this.fb.framebuffer.id, binding);

        var tDT = this.data.translucentDrawTargets;
        binding = new int[tDT.length];
        for (int i = 0; i < tDT.length; i++) {
            binding[i] = GL30.GL_COLOR_ATTACHMENT0+i;
            glNamedFramebufferTexture(this.fbTranslucent.framebuffer.id, GL30.GL_COLOR_ATTACHMENT0+i, tDT[i], 0);
        }
        glNamedFramebufferDrawBuffers(this.fbTranslucent.framebuffer.id, binding);

        this.fb.framebuffer.verify();
        this.fbTranslucent.framebuffer.verify();

        if (data.getUniforms() != null) {
            this.shaderUniforms = new GlBuffer(data.getUniforms().size());
        } else {
            this.shaderUniforms = null;
        }

        if (!this.data.skipShaderDepthHackFix) {
            this.shaderDepthHackFixTransformBlit = new FullscreenBlit(properties, "voxy:post/fullscreen2.vert", "voxy:post/noop.frag");
        } else {
            this.shaderDepthHackFixTransformBlit = null;
        }

        this.depthBlit = new FullscreenBlit(properties, "voxy:post/blit_texture_depth_cutout.frag");
        this.data.thePipeline = this;
    }

    @Override
    public void setupExtraModelBakeryData(ModelBakerySubsystem modelService) {
        modelService.factory.setCustomBlockStateMapping(WorldRenderingSettings.INSTANCE.getBlockStateIds());
    }

    @Override
    public void free() {
        if (this.data.thePipeline != this) {
            throw new IllegalStateException();
        }
        this.data.thePipeline = null;

        this.depthBlit.delete();
        this.fbTranslucent.free();

        if (this.shaderDepthHackFixTransformBlit != null) {
            this.shaderDepthHackFixTransformBlit.delete();
        }

        if (this.shaderUniforms != null) {
            this.shaderUniforms.free();
        }

        super.free0();
    }

    @Override
    public void preSetup(Viewport<?> viewport) {
        super.preSetup(viewport);
        if (this.shaderUniforms != null) {
            //Update the uniforms
            long ptr = UploadStream.INSTANCE.uploadTo(this.shaderUniforms);
            this.data.getUniforms().updater().accept(ptr);
            UploadStream.INSTANCE.commit();
        }
    }

    @Override
    public boolean supportsSourceViewport(int sourceFramebuffer, int x, int y, int width, int height,
                                          int targetWidth, int targetHeight) {
        if (x != 0 || y != 0) {
            return this.rejectSourceTarget("the active viewport has a non-zero origin");
        }
        if (width <= 0 || height <= 0 || targetWidth <= 0 || targetHeight <= 0) {
            return this.rejectSourceTarget("the active viewport has invalid dimensions");
        }
        if (targetWidth > width || targetHeight > height) {
            return this.rejectSourceTarget("the Voxy render scale is larger than the Iris source viewport");
        }

        int samplingWidth = this.data.useViewportDims ? width : targetWidth;
        int samplingHeight = this.data.useViewportDims ? height : targetHeight;
        return this.getValidatedSourceDepthTexture(sourceFramebuffer, width, height,
                samplingWidth, samplingHeight, targetWidth, targetHeight) != 0;
    }

    @Override
    public void clearTextureAndSamplerBindings(int count) {
        super.clearTextureAndSamplerBindings(count);
        int[] irisSamplerBindings = IrisRenderSystemAccessor.voxy$getSamplerBindings();
        for (int i = 0; i < Math.min(count, irisSamplerBindings.length); i++) {
            irisSamplerBindings[i] = 0;
        }
    }

    @Override
    public void setupOpaqueBlendState() {
        BlendModeOverride.OFF.apply();
    }

    @Override
    public void setupTranslucentBlendState() {
        DEFAULT_TRANSLUCENT_BLEND.apply();
    }

    @Override
    public void finishTranslucentBlendState() {
        BlendModeOverride.OFF.apply();
    }

    @Override
    protected int setup(Viewport<?> viewport, int sourceFramebuffer, int srcWidth, int srcHeight) {
        int samplingWidth = this.data.useViewportDims ? srcWidth : viewport.width;
        int samplingHeight = this.data.useViewportDims ? srcHeight : viewport.height;
        int sourceDepthTexture = this.validatedDepthTexture;
        if (sourceDepthTexture == 0) {
            return 0;
        }

        try {
            this.fb.resize(viewport.width, viewport.height);
            this.fbTranslucent.resize(viewport.width, viewport.height);
        } catch (RuntimeException e) {
            this.validatedDepthTexture = 0;
            this.rejectSourceTarget("Voxy could not create compatible views of the Iris render targets", e);
            return 0;
        }

        if (false) {//TODO: only do this if shader specifies
            //Clear the colour component
            glBindFramebuffer(GL_FRAMEBUFFER, this.fb.framebuffer.id);
            glClearColor(0, 0, 0, 0);
            glClear(GL_COLOR_BUFFER_BIT);
        }

        this.initDepthStencilFromTexture(sourceDepthTexture, this.fb.framebuffer.id, samplingWidth, samplingHeight,
                viewport.width, viewport.height);
        return this.fb.getDepthTex().id;
    }

    private int getValidatedSourceDepthTexture(int sourceFramebuffer, int viewportWidth, int viewportHeight,
                                               int samplingWidth, int samplingHeight, int targetWidth, int targetHeight) {
        // Iris reattaches its depth texture when render targets are recreated. This is the same
        // attachment-name query Voxy previously performed per frame; the expensive validation
        // below remains cached until the attachment or dimensions change.
        int depthTexture = glGetNamedFramebufferAttachmentParameteri(sourceFramebuffer, GL_DEPTH_ATTACHMENT,
                GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
        if (this.validatedSourceFramebuffer == sourceFramebuffer
                && this.validatedViewportWidth == viewportWidth
                && this.validatedViewportHeight == viewportHeight
                && this.validatedSamplingWidth == samplingWidth
                && this.validatedSamplingHeight == samplingHeight
                && this.validatedTargetWidth == targetWidth
                && this.validatedTargetHeight == targetHeight
                && this.validatedDepthAttachment == depthTexture) {
            return this.validatedDepthTexture;
        }

        this.validatedSourceFramebuffer = sourceFramebuffer;
        this.validatedViewportWidth = viewportWidth;
        this.validatedViewportHeight = viewportHeight;
        this.validatedSamplingWidth = samplingWidth;
        this.validatedSamplingHeight = samplingHeight;
        this.validatedTargetWidth = targetWidth;
        this.validatedTargetHeight = targetHeight;
        this.validatedDepthAttachment = depthTexture;
        this.validatedDepthTexture = 0;

        if (depthTexture <= 0) {
            this.rejectSourceTarget("the active Iris framebuffer has no readable depth texture");
            return 0;
        }

        if (glCheckNamedFramebufferStatus(sourceFramebuffer, GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
            this.rejectSourceTarget("the active Iris framebuffer is incomplete");
            return 0;
        }

        int attachmentType = glGetNamedFramebufferAttachmentParameteri(sourceFramebuffer, GL_DEPTH_ATTACHMENT,
                GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE);
        if (attachmentType != org.lwjgl.opengl.GL11C.GL_TEXTURE) {
            this.rejectSourceTarget("the active Iris depth attachment is not a texture");
            return 0;
        }

        int depthWidth = glGetTextureLevelParameteri(depthTexture, 0, org.lwjgl.opengl.GL11C.GL_TEXTURE_WIDTH);
        int depthHeight = glGetTextureLevelParameteri(depthTexture, 0, org.lwjgl.opengl.GL11C.GL_TEXTURE_HEIGHT);
        int depthFormat = glGetTextureLevelParameteri(depthTexture, 0, org.lwjgl.opengl.GL11C.GL_TEXTURE_INTERNAL_FORMAT);
        if (viewportWidth > depthWidth || viewportHeight > depthHeight
                || samplingWidth > depthWidth || samplingHeight > depthHeight
                || targetWidth > depthWidth || targetHeight > depthHeight) {
            this.rejectSourceTarget("the Iris depth texture is smaller than the sampled Voxy viewport");
            return 0;
        }
        if (!isSupportedDepthFormat(depthFormat)) {
            this.rejectSourceTarget("the Iris depth texture uses unsupported format 0x" + Integer.toHexString(depthFormat));
            return 0;
        }
        if (!this.areDrawTargetsLargeEnough(this.data.opaqueDrawTargets, targetWidth, targetHeight)
                || !this.areDrawTargetsLargeEnough(this.data.translucentDrawTargets, targetWidth, targetHeight)) {
            this.rejectSourceTarget("an Iris colour target is smaller than the Voxy viewport");
            return 0;
        }

        this.validatedDepthTexture = depthTexture;
        return depthTexture;
    }

    private boolean areDrawTargetsLargeEnough(int[] textures, int width, int height) {
        for (int texture : textures) {
            if (texture <= 0
                    || glGetTextureLevelParameteri(texture, 0, org.lwjgl.opengl.GL11C.GL_TEXTURE_WIDTH) < width
                    || glGetTextureLevelParameteri(texture, 0, org.lwjgl.opengl.GL11C.GL_TEXTURE_HEIGHT) < height) {
                return false;
            }
        }
        return true;
    }

    private static boolean isSupportedDepthFormat(int format) {
        return switch (format) {
            case org.lwjgl.opengl.GL11C.GL_DEPTH_COMPONENT,
                    org.lwjgl.opengl.GL14C.GL_DEPTH_COMPONENT16,
                    org.lwjgl.opengl.GL14C.GL_DEPTH_COMPONENT24,
                    org.lwjgl.opengl.GL14C.GL_DEPTH_COMPONENT32,
                    GL_DEPTH_COMPONENT32F,
                    GL_DEPTH_STENCIL,
                    GL_DEPTH24_STENCIL8,
                    GL_DEPTH32F_STENCIL8 -> true;
            default -> false;
        };
    }

    private boolean rejectSourceTarget(String reason) {
        return this.rejectSourceTarget(reason, null);
    }

    private boolean rejectSourceTarget(String reason, RuntimeException cause) {
        if (!this.warnedUnsupportedTarget) {
            this.warnedUnsupportedTarget = true;
            if (cause == null) {
                Logger.warn("Skipping the Voxy Iris sub-pipeline because", reason);
            } else {
                Logger.warn("Skipping the Voxy Iris sub-pipeline because", reason, cause);
            }
        }
        return false;
    }

    @Override
    protected void postOpaquePreTranslucent(Viewport<?> viewport, int sourceFrameBuffer) {
        if (this.shaderDepthHackFixTransformBlit != null) {
            this.fb.bind();
            glEnable(GL_DEPTH_TEST);
            glColorMask(false, false, false, false);
            glDepthFunc(GL_ALWAYS);
            glStencilFunc(GL_EQUAL, 0, 0xFF);//set the depth to 1 where the mask is 0
            this.shaderDepthHackFixTransformBlit.blit();
            glStencilFunc(GL_EQUAL, 1, 0xFF);//revert the mask test
            glDepthFunc(this.properties.closerEqualDepthCompare());
            glColorMask(true, true, true, true);
        }

        glTextureBarrier();

        int msk = GL_DEPTH_BUFFER_BIT|GL_STENCIL_BUFFER_BIT;
        if (true) {//TODO: make shader specified
            if (false) {//TODO: only do this if shader specifies
                glBindFramebuffer(GL_FRAMEBUFFER, this.fbTranslucent.framebuffer.id);
                glClearColor(0, 0, 0, 0);
                glClear(GL_COLOR_BUFFER_BIT);
            }
        } else {
            msk |= GL_COLOR_BUFFER_BIT;
        }
        glBlitNamedFramebuffer(this.fb.framebuffer.id, this.fbTranslucent.framebuffer.id, 0,0, viewport.width, viewport.height, 0,0, viewport.width, viewport.height, msk, GL_NEAREST);
    }

    @Override
    protected void finish(Viewport<?> viewport, int sourceFrameBuffer, int srcWidth, int srcHeight) {
        if (this.data.renderToVanillaDepth && srcWidth == viewport.width && srcHeight == viewport.height) {
            glColorMask(false, false, false, false);
            AbstractRenderPipeline.transformBlitDepth(
                    this.depthBlit,
                    this.fbTranslucent.getDepthTex().id,
                    sourceFrameBuffer,
                    viewport,
                    new Matrix4f(viewport.vanillaProjection).mul(viewport.modelView)
            );
            glColorMask(true, true, true, true);
        } else {
            // normally disabled by AbstractRenderPipeline but since we are skipping it we do it here
            glDisable(GL_STENCIL_TEST);
            glDisable(GL_DEPTH_TEST);
        }
    }


    @Override
    public void bindUniforms() {
        this.bindUniforms(UNIFORM_BINDING_POINT);
    }

    @Override
    public void bindUniforms(int bindingPoint) {
        if (this.shaderUniforms != null) {
            GL30.glBindBufferBase(GL_UNIFORM_BUFFER, bindingPoint, this.shaderUniforms.id);// todo: dont randomly select this to 5
        }
    }

    private void doBindings() {
        this.bindUniforms();
        if (this.data.getSsboSet() != null) {
            this.data.getSsboSet().bindingFunction().accept(BASE_BUFFER_BINDING_INDEX);
        }
        if (this.data.getImageSet() != null) {
            this.data.getImageSet().bindingFunction().accept(BASE_SAMPLER_BINDING_INDEX);
        }
    }
    @Override
    public void setupAndBindOpaque(Viewport<?> viewport) {
        this.fb.bind();
        this.doBindings();
    }

    @Override
    public void setupAndBindTranslucent(Viewport<?> viewport) {
        this.fbTranslucent.bind();
        this.doBindings();
        if (this.data.getBlender() != null) {
            this.data.getBlender().run();
        }
    }

    @Override
    public void addDebug(List<String> debug) {
        debug.add("Using: " + this.getClass().getSimpleName());
        super.addDebug(debug);
    }

    private StringBuilder buildGenericShaderHeader(AbstractSectionRenderer<?, ?> renderer, String input) {
        StringBuilder builder = new StringBuilder(input).append("\n\n\n");

        if (this.data.getUniforms() != null) {
            builder.append("layout(binding = "+UNIFORM_BINDING_POINT+", std140) uniform ShaderUniformBindings ")
                    .append(this.data.getUniforms().layout())
                    .append(";\n\n");
        }

        if (this.data.getSsboSet() != null) {
            builder.append("#define BUFFER_BINDING_INDEX_BASE ").append(BASE_BUFFER_BINDING_INDEX).append('\n');
            builder.append(this.data.getSsboSet().layout()).append("\n\n");
        }

        if (this.data.getImageSet() != null) {
            builder.append("#define BASE_SAMPLER_BINDING_INDEX ").append(BASE_SAMPLER_BINDING_INDEX).append('\n');
            builder.append(this.data.getImageSet().layout()).append("\n\n");
        }

        return builder.append("\n\n");
    }



    @Override
    public String patchOpaqueShader(AbstractSectionRenderer<?, ?> renderer, String input) {
        var builder = this.buildGenericShaderHeader(renderer, input);

        builder.append(this.data.opaqueFragPatch());

        return builder.toString();
    }

    @Override
    public String patchTranslucentShader(AbstractSectionRenderer<?, ?> renderer, String input) {
        if (this.data.translucentFragPatch() == null) return null;

        var builder = this.buildGenericShaderHeader(renderer, input);
        builder.append(this.data.translucentFragPatch());
        return builder.toString();
    }

    @Override
    public boolean hasTAA() {
        return this.data.TAA != null;
    }

    @Override
    public String taaFunction(String functionName) {
        return this.taaFunction(UNIFORM_BINDING_POINT, functionName);
    }

    @Override
    public String taaFunction(int uboBindingPoint, String functionName) {
        if (this.data.TAA == null) {
            return null;
        }

        var builder = new StringBuilder();

        if (this.data.getUniforms() != null) {
            builder.append("layout(binding = "+uboBindingPoint+", std140) uniform ShaderUniformBindings ")
                    .append(this.data.getUniforms().layout())
                    .append(";\n\n");
        }

        builder.append("vec2 ").append(functionName).append("()\n");
        builder.append(this.data.TAA);
        builder.append("\n");
        return builder.toString();
    }

    @Override
    public float[] getRenderScalingFactor() {
        return this.data.resolutionScale;
    }
}
