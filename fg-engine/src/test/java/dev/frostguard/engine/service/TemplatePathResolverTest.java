package dev.frostguard.engine.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TemplatePathResolverTest {

    @TempDir
    Path tempDir;

    @Test
    void resolvesPackageRelativeTemplatePathsFromWorkingDirectory() {
        String originalUserDir = System.getProperty("user.dir");
        System.setProperty("user.dir", tempDir.toString());
        try {
            String resolved = TemplatePathResolver.resolveFileReference(
                    "templates/deals/deadshot/event_tab.png");

            assertEquals(tempDir.resolve("templates/deals/deadshot/event_tab.png")
                    .normalize().toString(), resolved);
        } finally {
            System.setProperty("user.dir", originalUserDir);
        }
    }

    @Test
    void classifiesEnumsSeparatelyFromFileReferences() {
        assertFalse(TemplatePathResolver.isFileReference("HOME_DEALS_BUTTON"));
        assertTrue(TemplatePathResolver.isFileReference("templates/deals/deadshot/event_tab.png"));
        assertTrue(TemplatePathResolver.isFileReference("file:///tmp/event_tab.png"));
    }
}
