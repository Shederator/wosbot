package dev.frostguard.engine.diagnostics;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.engine.service.ConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Global switch for desktop frames. A missing row stays off.
 */
public final class DesktopSnapshotSettings {

    private static final Logger logger = LoggerFactory.getLogger(DesktopSnapshotSettings.class);

    private DesktopSnapshotSettings() {
    }

    public static boolean enabled() {
        try {
            return enabled(ConfigService.obtain().loadGlobalSettings());
        } catch (RuntimeException failure) {
            logger.warn("Desktop snapshot setting could not be read: {}", failure.toString());
            return false;
        }
    }

    static boolean enabled(Map<String, String> settings) {
        if (settings == null) {
            return false;
        }
        return Boolean.parseBoolean(settings.getOrDefault(
                ConfigurationKeyEnum.DESKTOP_SNAPSHOT_ENABLED_BOOL.name(),
                ConfigurationKeyEnum.DESKTOP_SNAPSHOT_ENABLED_BOOL.getDefaultValue()));
    }
}
