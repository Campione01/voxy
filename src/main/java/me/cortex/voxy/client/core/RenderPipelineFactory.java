package me.cortex.voxy.client.core;

import me.cortex.voxy.client.core.rendering.hierachical.AsyncNodeManager;
import me.cortex.voxy.client.core.rendering.hierachical.HierarchicalOcclusionTraverser;
import me.cortex.voxy.client.core.rendering.hierachical.NodeCleaner;
import me.cortex.voxy.client.core.util.IrisUtil;
import me.cortex.voxy.client.iris.IGetIrisVoxyPipelineData;
import me.cortex.voxy.common.Logger;
import net.irisshaders.iris.Iris;

import java.util.function.BooleanSupplier;

public class RenderPipelineFactory {
    public static AbstractRenderPipeline createPipeline(RenderProperties properties, AsyncNodeManager nodeManager, NodeCleaner nodeCleaner, HierarchicalOcclusionTraverser traversal, BooleanSupplier frexSupplier) {
        if (IrisUtil.irisShaderPackEnabled()) {
            var pipeline = createIrisPipeline(properties, nodeManager, nodeCleaner, traversal, frexSupplier);
            if (pipeline == null) {
                throw new IllegalStateException("Iris owns terrain rendering, but no compatible Voxy Iris pipeline is available");
            }
            return pipeline;
        }
        return new NormalRenderPipeline(properties, nodeManager, nodeCleaner, traversal, frexSupplier);
    }

    public static boolean isIrisPipelineAvailable() {
        return !IrisUtil.irisShaderPackEnabled() || getIrisPipelineData() != null;
    }

    private static me.cortex.voxy.client.iris.IrisVoxyRenderPipelineData getIrisPipelineData() {
        if (!IrisUtil.SHADER_SUPPORT) {
            return null;
        }
        var irisPipe = Iris.getPipelineManager().getPipelineNullable();
        if (irisPipe instanceof IGetIrisVoxyPipelineData getVoxyPipeData) {
            return getVoxyPipeData.voxy$getPipelineData();
        }
        return null;
    }

    private static AbstractRenderPipeline createIrisPipeline(RenderProperties properties, AsyncNodeManager nodeManager, NodeCleaner nodeCleaner, HierarchicalOcclusionTraverser traversal, BooleanSupplier frexSupplier) {
        var pipeData = getIrisPipelineData();
        if (pipeData == null) {
            return null;
        }
        Logger.info("Creating voxy iris render pipeline");
        return new IrisVoxyRenderPipeline(properties, pipeData, nodeManager, nodeCleaner, traversal, frexSupplier);
    }
}
