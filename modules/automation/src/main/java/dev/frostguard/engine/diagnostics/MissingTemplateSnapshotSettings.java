package dev.frostguard.engine.diagnostics;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.engine.service.ConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.function.Supplier;

/**
 * Master switch for task diagnostic snapshots, including missing-template
 * evidence. The legacy setting key is retained, and a missing row stays off.
 */
public final class MissingTemplateSnapshotSettings {

    private static final Logger logger = LoggerFactory.getLogger(MissingTemplateSnapshotSettings.class);

    private MissingTemplateSnapshotSettings() {
    }

    public static boolean enabled() {
        try {
            return enabled(ConfigService.obtain().loadGlobalSettings());
        } catch (RuntimeException failure) {
            logger.warn("Diagnostic snapshot setting could not be read: {}", failure.toString());
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
