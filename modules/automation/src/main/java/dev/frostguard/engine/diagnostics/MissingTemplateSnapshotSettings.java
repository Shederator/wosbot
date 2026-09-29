package dev.frostguard.engine.diagnostics;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.engine.service.ConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.function.Supplier;

/**
 * Global switch for a snapshot when a required event template is not installed.
 * A missing row stays off. The task still logs its next visit without a frame.
 */
public final class MissingTemplateSnapshotSettings {

    private static final Logger logger = LoggerFactory.getLogger(MissingTemplateSnapshotSettings.class);

    private MissingTemplateSnapshotSettings() {
    }

    public static boolean enabled() {
        try {
            return enabled(ConfigService.obtain().loadGlobalSettings());
        } catch (RuntimeException failure) {
            logger.warn("Missing-template snapshot setting could not be read: {}", failure.toString());
            return false;
        }
    }

    static boolean enabled(Map<String, String> settings) {
        if (settings == null) {
            return false;
        }
        return Boolean.parseBoolean(settings.getOrDefault(
                ConfigurationKeyEnum.MISSING_TEMPLATE_SNAPSHOT_ENABLED_BOOL.name(),
                ConfigurationKeyEnum.MISSING_TEMPLATE_SNAPSHOT_ENABLED_BOOL.getDefaultValue()));
    }

    public static String note(boolean enabled, String templateName, Supplier<String> snapshot) {
        if (!enabled) {
            return "";
        }
        return "missing template " + templateName + "; " + snapshot.get();
    }
}
