package dev.frostguard.engine.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TemplatePathResolverTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void clearRootOverride() {
        System.clearProperty(TemplatePathResolver.ROOT_PROPERTY);
    }

    @Test
    void treatsTemplateEnumConstantsAsEnumNamesNotFiles() {
        assertFalse(TemplatePathResolver.isFileReference("HOME_DEALS_BUTTON"));
        assertFalse(TemplatePathResolver.isFileReference("GAME_HOME_FURNACE"));
    }

    @Test
    void treatsBlankOrMissingValuesAsEnumNames() {
        assertFalse(TemplatePathResolver.isFileReference(null));
        assertFalse(TemplatePathResolver.isFileReference("  "));
    }

    @Test
    void recognisesEveryShapeOfFileReference() {
        assertTrue(TemplatePathResolver.isFileReference("file:///home/op/tpl.png"));
        assertTrue(TemplatePathResolver.isFileReference("templates/deals/event_tab.png"));
        assertTrue(TemplatePathResolver.isFileReference("/opt/frostguard/tpl.png"));
        assertTrue(TemplatePathResolver.isFileReference("C:\\Users\\op\\tpl.png"));
        assertTrue(TemplatePathResolver.isFileReference("templates\\deals\\event_tab.png"));
        assertTrue(TemplatePathResolver.isFileReference("event_tab.png"));
    }

    @Test
    void keepsAbsolutePathsFromThePickerUnchanged() {
        Path absolute = tempDir.resolve("chosen.png").toAbsolutePath();

        String resolved = TemplatePathResolver.resolveFileReference(
                TemplatePathResolver.FILE_PREFIX + absolute);

        assertEquals(absolute.normalize().toString(), resolved);
    }

    /**
     * A shared task ships a relative template path. It must resolve against the
     * installation root rather than whichever directory launched the process.
     */
    @Test
    void resolvesRelativeReferenceAgainstTheInstallationRoot() throws Exception {
        Path template = tempDir.resolve("templates").resolve("deals").resolve("event_tab.png");
        Files.createDirectories(template.getParent());
        Files.writeString(template, "png");
        System.setProperty(TemplatePathResolver.ROOT_PROPERTY, tempDir.toString());

        String resolved = TemplatePathResolver.resolveFileReference("templates/deals/event_tab.png");

        assertEquals(template.toAbsolutePath().normalize().toString(), resolved);
    }

    /**
     * Bundled assets are addressed as classpath resources ("/templates/…")
     * elsewhere; on disk the same asset is staged without the leading separator.
     */
    @Test
    void resolvesClasspathStyleBundledAssetPaths() throws Exception {
        Path template = tempDir.resolve("templates").resolve("event_tab.png");
        Files.createDirectories(template.getParent());
        Files.writeString(template, "png");
        System.setProperty(TemplatePathResolver.ROOT_PROPERTY, tempDir.toString());

        String resolved = TemplatePathResolver.resolveFileReference("/templates/event_tab.png");

        assertEquals(template.toAbsolutePath().normalize().toString(), resolved);
    }

    @Test
    void resolvesRelativeReferenceAgainstImportedDefinitionDirectoryFirst() throws Exception {
        Path importedDirectory = tempDir.resolve("shared-task");
        Path template = importedDirectory.resolve("event_tab.png");
        Files.createDirectories(importedDirectory);
        Files.writeString(template, "png");
        System.setProperty(TemplatePathResolver.ROOT_PROPERTY, tempDir.resolve("install").toString());

        String resolved = TemplatePathResolver.resolveFileReference(
                "event_tab.png", importedDirectory);

        assertEquals(template.toAbsolutePath().normalize().toString(), resolved);
    }

    /** An unresolvable reference still reports a concrete path for the log. */
    @Test
    void returnsAnAbsoluteCandidateWhenNoRootHoldsTheTemplate() {
        System.setProperty(TemplatePathResolver.ROOT_PROPERTY, tempDir.toString());

        String resolved = TemplatePathResolver.resolveFileReference("templates/absent.png");

        assertEquals(tempDir.resolve("templates").resolve("absent.png")
                .toAbsolutePath().normalize().toString(), resolved);
    }

    @Test
    void rejectsBlankReferences() {
        assertThrows(IllegalArgumentException.class,
                () -> TemplatePathResolver.resolveFileReference(null));
        assertThrows(IllegalArgumentException.class,
                () -> TemplatePathResolver.resolveFileReference("   "));
        assertThrows(IllegalArgumentException.class,
                () -> TemplatePathResolver.resolveFileReference(TemplatePathResolver.FILE_PREFIX));
    }
}
