package me.cortex.voxy.common.platform;

import java.nio.file.Path;

public interface PlatformUtil {
    boolean isModLoaded(String modId);

    Path getModRootPath(String modId);

    String getModVersion(String modId);

    Path getReplayStoragePath(boolean flashbackInstalled);

    Path getConfigDir();

    boolean isDedicatedServer();
}
