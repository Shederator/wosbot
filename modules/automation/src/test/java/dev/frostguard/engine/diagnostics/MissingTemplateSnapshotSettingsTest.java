package dev.frostguard.engine.diagnostics;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MissingTemplateSnapshotSettingsTest {

    @Test
    void missingOrFalseSettingStaysOffAndDoesNotCapture() {
        assertEquals("false", ConfigurationKeyEnum.MISSING_TEMPLATE_SNAPSHOT_ENABLED_BOOL.getDefaultValue());
        assertFalse(MissingTemplateSnapshotSettings.enabled(null));
        assertFalse(MissingTemplateSnapshotSettings.enabled(Map.of()));
        assertFalse(MissingTemplateSnapshotSettings.enabled(Map.of(
                ConfigurationKeyEnum.MISSING_TEMPLATE_SNAPSHOT_ENABLED_BOOL.name(), "false")));

        AtomicInteger captures = new AtomicInteger();
        assertEquals("", MissingTemplateSnapshotSettings.note(false, "mercenary-completion", () -> {
            captures.incrementAndGet();
            return "snapshot=test";
        }));
        assertEquals(0, captures.get());
    }

    @Test
    void enabledSettingNamesTheMissingTemplateAndCapturesOnce() {
        assertTrue(MissingTemplateSnapshotSettings.enabled(Map.of(
                ConfigurationKeyEnum.MISSING_TEMPLATE_SNAPSHOT_ENABLED_BOOL.name(), "true")));

        String note = MissingTemplateSnapshotSettings.note(true, "mercenary-completion", () -> "snapshot=test");

        assertTrue(note.contains("missing template mercenary-completion"));
        assertTrue(note.contains("snapshot=test"));
    }
}
