package dev.frostguard.api.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FrostguardPathsTest {
    @TempDir
    Path tempDir;

    @AfterEach
    void clearOverrides() {
        System.clearProperty(FrostguardPaths.DATA_PROPERTY);
        System.clearProperty(FrostguardPaths.HOME_PROPERTY);
    }

    @Test
    void explicitOverridesDoNotDependOnWorkingDirectory() {
        Path data = tempDir.resolve("persistent");
        Path home = tempDir.resolve("program");
        System.setProperty(FrostguardPaths.DATA_PROPERTY, data.toString());
        System.setProperty(FrostguardPaths.HOME_PROPERTY, home.toString());

        FrostguardPaths paths = FrostguardPaths.resolve(FrostguardPathsTest.class);

        assertEquals(data.toAbsolutePath(), paths.dataHome());
        assertEquals(home.toAbsolutePath(), paths.applicationHome());
    }

    @Test
    void explodedRepositoryClassesUseCheckoutDataDirectory() {
        FrostguardPaths paths = FrostguardPaths.resolve(FrostguardPathsTest.class);

        Path repository = Path.of(FrostguardPathsTest.class.getProtectionDomain()
                .getCodeSource().getLocation().getPath()).toAbsolutePath();
        while (repository != null && !java.nio.file.Files.isDirectory(repository.resolve("fg-app"))) {
            repository = repository.getParent();
        }

        assertEquals(repository.resolve(".frostguard/data").normalize(), paths.dataHome());
    }
}
