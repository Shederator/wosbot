package dev.frostguard.app.panel.update;

import dev.frostguard.api.runtime.RuntimeChannel;
import dev.frostguard.update.UpdateException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdateLayoutControllerTest {
    @TempDir
    Path tempDir;

    @Test
    void formatsInstallerSizesForReview() {
        assertEquals("512 B", UpdateLayoutController.formatSize(512));
        assertEquals("1.5 KiB", UpdateLayoutController.formatSize(1536));
        assertEquals("273.5 MiB", UpdateLayoutController.formatSize(286_739_610));
    }

    @Test
    void offersChannelSwitchingForInstalledAndPrChannelBuilds() {
        assertTrue(UpdateLayoutController.supportsChannelSwitch(RuntimeChannel.STABLE));
        assertTrue(UpdateLayoutController.supportsChannelSwitch(RuntimeChannel.NIGHTLY));
        assertFalse(UpdateLayoutController.supportsChannelSwitch(RuntimeChannel.DEVELOPMENT));
    }

    @Test
    void resolvesOnlyAnExistingNativeLauncherForUpdateRestart() throws Exception {
        String previous = System.getProperty("jpackage.app-path");
        Path launcher = Files.writeString(tempDir.resolve("Frostguard Nightly.exe"), "test");
        try {
            System.setProperty("jpackage.app-path", launcher.toString());
            assertEquals(launcher.toAbsolutePath(), UpdateLayoutController.currentNativeLauncher());

            System.setProperty("jpackage.app-path", tempDir.resolve("missing.exe").toString());
            assertThrows(UpdateException.class, UpdateLayoutController::currentNativeLauncher);
        } finally {
            if (previous == null) {
                System.clearProperty("jpackage.app-path");
            } else {
                System.setProperty("jpackage.app-path", previous);
            }
        }
    }
}
