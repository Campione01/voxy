package me.cortex.voxy.client.core.util;

import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.iris.IrisVoxyRenderPipelineData;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.commonImpl.VoxyCommon;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.api.v0.IrisApi;
import net.irisshaders.iris.shadows.ShadowRenderer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;

public class IrisUtil {
    private static final int MAX_PATCH_BYTES = 1 << 20;
    private static final Pattern SUPPORTED_PATCH_VERSION = Pattern.compile("\"version\"\\s*:\\s*1(?!\\d)");
    private static final Pattern SAMPLER_DECLARATION = Pattern.compile("\"samplers\"\\s*:");
    private static final Pattern SSBO_DECLARATION = Pattern.compile("\"ssbos\"\\s*:");
    private static volatile boolean voxyMacroActive;

    public record CapturedViewportParameters(ChunkRenderMatrices matrices, double x, double y, double z) {
        public Viewport<?> apply(VoxyRenderSystem vrs) {
            return vrs.setupViewport(this.matrices.projection(), this.matrices.modelView(), this.x, this.y, this.z);
        }
    }

    public static CapturedViewportParameters CAPTURED_VIEWPORT_PARAMETERS;

    public static final boolean IRIS_INSTALLED = VoxyCommon.getPlatformUtil().isModLoaded("iris")
            || VoxyCommon.getPlatformUtil().isModLoaded("oculus");
    public static final boolean SHADER_SUPPORT = true;//System.getProperty("voxy.enableExperimentalIrisPipeline", "false").equalsIgnoreCase("true");


    private static boolean irisShadowActive0() {
        return ShadowRenderer.ACTIVE;
    }

    public static boolean irisShadowActive() {
        return IRIS_INSTALLED && irisShadowActive0();
    }

    public static void reload() {
        if (IRIS_INSTALLED) reload0();
    }

    private static void reload0() {
        try {
            if (IrisApi.getInstance().isShaderPackInUse()||IrisApi.getInstance().getConfig().areShadersEnabled()) {//Only reload if there is a shaderpack
                Iris.reload();
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static boolean irisShaderPackEnabled0() {
        return Iris.getCurrentPack().isPresent();
    }

    public static boolean irisShaderPackEnabled() {
        return IRIS_INSTALLED && irisShaderPackEnabled0();
    }
    private static boolean irisShadersEnabledInConfig0() {
        return !Iris.getCurrentPack().isEmpty();
    }
    public static boolean irisShadersEnabledInConfig() {
        return IRIS_INSTALLED && irisShadersEnabledInConfig0();
    }

    public static boolean prepareVoxyMacro(boolean renderingEnabled) {
        voxyMacroActive = renderingEnabled && SHADER_SUPPORT && IRIS_INSTALLED && hasSupportedVoxyPatch();
        return voxyMacroActive;
    }

    public static boolean isVoxyMacroActive() {
        return voxyMacroActive;
    }

    private static boolean hasSupportedVoxyPatch() {
        try {
            var config = Iris.getIrisConfig();
            if (config == null || !config.areShadersEnabled()) return false;
            var packName = config.getShaderPackName().orElse(null);
            if (packName == null) return false;

            Path pack = Iris.getShaderpacksDirectory().resolve(packName);
            if (Files.isDirectory(pack)) {
                Path shaders = pack.resolve("shaders");
                if (!Files.isDirectory(shaders)) return false;
                try (var files = Files.find(shaders, 3,
                        (path, attributes) -> attributes.isRegularFile() && path.getFileName().toString().equalsIgnoreCase("voxy.json"))) {
                    var patches = files.toList();
                    return !patches.isEmpty() && patches.stream().allMatch(IrisUtil::isSupportedPatchFile);
                }
            }

            if (!Files.isRegularFile(pack)) return false;
            try (ZipFile zip = new ZipFile(pack.toFile())) {
                boolean foundPatch = false;
                var entries = zip.entries();
                while (entries.hasMoreElements()) {
                    var entry = entries.nextElement();
                    if (entry.isDirectory() || !isVoxyPatchPath(entry.getName())) continue;
                    foundPatch = true;
                    if (entry.getSize() > MAX_PATCH_BYTES) return false;
                    try (InputStream input = zip.getInputStream(entry)) {
                        if (!isSupportedPatch(input)) return false;
                    }
                }
                return foundPatch;
            }
        } catch (Exception e) {
            Logger.warn("Unable to preflight the selected Iris shader pack for Voxy; Voxy integration will not be advertised", e);
        }
        return false;
    }

    private static boolean isSupportedPatchFile(Path path) {
        try {
            if (Files.size(path) > MAX_PATCH_BYTES) return false;
            try (InputStream input = Files.newInputStream(path)) {
                return isSupportedPatch(input);
            }
        } catch (IOException e) {
            Logger.warn("Unable to read Voxy shader metadata", path, e);
            return false;
        }
    }

    private static boolean isSupportedPatch(InputStream input) throws IOException {
        byte[] bytes = input.readNBytes(MAX_PATCH_BYTES + 1);
        if (bytes.length > MAX_PATCH_BYTES) return false;
        String source = new String(bytes, StandardCharsets.UTF_8);
        return SUPPORTED_PATCH_VERSION.matcher(source).find()
                && hasSupportedSamplerDeclaration(source)
                && hasSupportedSsboDeclaration(source);
    }

    private static boolean hasSupportedSamplerDeclaration(String source) {
        var declaration = SAMPLER_DECLARATION.matcher(source);
        if (!declaration.find()) return true;

        int objectStart = skipMetadataTrivia(source, declaration.end());
        if (objectStart >= source.length()) return false;
        if (source.startsWith("null", objectStart)) return true;
        if (source.charAt(objectStart) != '{') return false;

        int count = countTopLevelStringMapKeys(source, objectStart, false);
        return count >= 0 && count <= IrisVoxyRenderPipelineData.MAX_FORWARDED_SAMPLER_COUNT;
    }

    private static boolean hasSupportedSsboDeclaration(String source) {
        var declaration = SSBO_DECLARATION.matcher(source);
        if (!declaration.find()) return true;

        int objectStart = skipMetadataTrivia(source, declaration.end());
        if (objectStart >= source.length()) return false;
        if (source.startsWith("null", objectStart)) return true;
        if (source.charAt(objectStart) != '{') return false;

        int count = countTopLevelStringMapKeys(source, objectStart, true);
        return count >= 0 && count <= IrisVoxyRenderPipelineData.MAX_FORWARDED_SSBO_COUNT;
    }

    private static int countTopLevelStringMapKeys(String source, int objectStart, boolean indexedKeys) {
        final int keyOrEnd = 0;
        final int value = 1;
        final int commaOrEnd = 2;
        int state = keyOrEnd;
        int count = 0;

        for (int i = objectStart + 1; i < source.length();) {
            i = skipMetadataTrivia(source, i);
            if (i >= source.length()) return -1;

            char current = source.charAt(i);
            if (state == commaOrEnd) {
                if (current == '}') return count;
                if (current != ',') return -1;
                state = keyOrEnd;
                i++;
                continue;
            }
            if (state == value) {
                if (current != '"') return -1;
                int end = findMetadataStringEnd(source, i);
                if (end < 0) return -1;
                i = end + 1;
                state = commaOrEnd;
                continue;
            }

            if (current == '}') return count;
            if (current != '"') return -1;
            int end = findMetadataStringEnd(source, i);
            if (end < 0) return -1;
            int afterKey = skipMetadataTrivia(source, end + 1);
            if (afterKey >= source.length() || source.charAt(afterKey) != ':') return -1;

            if (indexedKeys) {
                final int index;
                try {
                    index = Integer.parseInt(source.substring(i + 1, end));
                } catch (NumberFormatException e) {
                    return -1;
                }
                if (index < -1) return -1;
                if (index != -1) count++;
            } else {
                count++;
            }
            i = afterKey + 1;
            state = value;
        }
        return -1;
    }

    private static int findMetadataStringEnd(String source, int start) {
        boolean escaped = false;
        for (int i = start + 1; i < source.length(); i++) {
            char current = source.charAt(i);
            if (escaped) {
                escaped = false;
            } else if (current == '\\') {
                escaped = true;
            } else if (current == '"') {
                return i;
            }
        }
        return -1;
    }

    private static int skipMetadataTrivia(String source, int offset) {
        int cursor = offset;
        while (cursor < source.length()) {
            char current = source.charAt(cursor);
            if (Character.isWhitespace(current)) {
                cursor++;
                continue;
            }
            if (current == '#') {
                int newline = source.indexOf('\n', cursor + 1);
                if (newline < 0) return source.length();
                cursor = newline + 1;
                continue;
            }
            if (current == '/' && cursor + 1 < source.length()) {
                char next = source.charAt(cursor + 1);
                if (next == '/') {
                    int newline = source.indexOf('\n', cursor + 2);
                    if (newline < 0) return source.length();
                    cursor = newline + 1;
                    continue;
                }
                if (next == '*') {
                    int commentEnd = source.indexOf("*/", cursor + 2);
                    if (commentEnd < 0) return source.length();
                    cursor = commentEnd + 2;
                    continue;
                }
            }
            return cursor;
        }
        return cursor;
    }

    private static boolean isVoxyPatchPath(String path) {
        String normalized = path.replace('\\', '/').toLowerCase(Locale.ROOT);
        int shaders = normalized.indexOf("shaders/");
        return shaders >= 0 && normalized.substring(shaders + "shaders/".length()).endsWith("voxy.json");
    }
}
