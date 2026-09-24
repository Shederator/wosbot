package dev.frostguard.engine.diagnostics;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesktopSnapshotSettingsTest {

    @Test
    void missingOrFalseSettingStaysOff() {
        assertEquals("false", ConfigurationKeyEnum.DESKTOP_SNAPSHOT_ENABLED_BOOL.getDefaultValue());
        assertFalse(DesktopSnapshotSettings.enabled(null));
        assertFalse(DesktopSnapshotSettings.enabled(Map.of()));
        assertFalse(DesktopSnapshotSettings.enabled(Map.of(
                ConfigurationKeyEnum.DESKTOP_SNAPSHOT_ENABLED_BOOL.name(), "false")));
        assertTrue(DesktopSnapshotSettings.enabled(Map.of(
                ConfigurationKeyEnum.DESKTOP_SNAPSHOT_ENABLED_BOOL.name(), "true")));
    }
}
