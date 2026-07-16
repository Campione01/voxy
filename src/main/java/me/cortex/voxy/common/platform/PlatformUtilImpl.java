package me.cortex.voxy.common.platform;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.fml.loading.LoadingModList;

import java.nio.file.Path;

public final class PlatformUtilImpl implements PlatformUtil {
    @Override
    public boolean isModLoaded(String modId) {
        var loadingList = LoadingModList.get();
        if (loadingList != null && loadingList.getModFileById(modId) != null) {
            return true;
        }

        var modList = ModList.get();
        return modList != null && modList.isLoaded(modId);
    }

    @Override
    public Path getModRootPath(String modId) {
        var modList = ModList.get();
        if (modList != null) {
            var modFile = modList.getModFileById(modId);
            if (modFile != null) {
                return modFile.getFile().getSecureJar().getRootPath();
            }
        }

        var loadingList = LoadingModList.get();
        if (loadingList != null) {
            var modFile = loadingList.getModFileById(modId);
            if (modFile != null) {
                return modFile.getFile().getSecureJar().getRootPath();
            }
        }
        return null;
    }

    @Override
    public String getModVersion(String modId) {
        var loadingList = LoadingModList.get();
        if (loadingList != null) {
            var modFile = loadingList.getModFileById(modId);
            if (modFile != null) {
                return modFile.versionString();
            }
        }

        var modList = ModList.get();
        if (modList != null) {
            var modFile = modList.getModFileById(modId);
            if (modFile != null) {
                return modFile.versionString();
            }
        }
        return null;
    }

    @Override
    public Path getReplayStoragePath(boolean flashbackInstalled) {
        return null;
    }

    @Override
    public Path getConfigDir() {
        return FMLPaths.CONFIGDIR.get();
    }

    @Override
    public boolean isDedicatedServer() {
        return FMLLoader.getDist() == Dist.DEDICATED_SERVER;
    }
}
