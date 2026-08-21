package dev.frostguard.app.panel.notification;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NotificationCenterLayoutTest {

    @Test
    void bellPrecedesUptimeAndDrawerStaysInsideLauncherWindow() throws Exception {
        Path root = repositoryRoot();
        String launcher = Files.readString(
                root.resolve("modules/desktop/src/main/resources/layout/LauncherLayout.fxml"));
        String drawer = Files.readString(
                root.resolve("modules/desktop/src/main/resources/layout/NotificationCenter.fxml"));

        assertTrue(launcher.indexOf("notificationButtonContainer") < launcher.indexOf("labelRunTime"));
        assertTrue(launcher.contains("fx:id=\"notificationDrawerHost\""));
        assertTrue(launcher.contains("StackPane.alignment=\"CENTER_RIGHT\""));
        assertFalse(drawer.contains("<Dialog"));
        assertFalse(drawer.contains("<Stage"));
    }

    private static Path repositoryRoot() {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isDirectory(root.resolve("modules/desktop"))) {
            root = root.getParent();
        }
        if (root == null) {
            throw new IllegalStateException("Repository root not found");
        }
        return root;
    }
}
