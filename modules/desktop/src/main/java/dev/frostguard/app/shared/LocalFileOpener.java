package dev.frostguard.app.shared;

import java.awt.Desktop;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Opens a file or folder with the platform file manager.
 * Windows uses {@code explorer.exe} because {@link Desktop#open} often returns
 * without showing a window when called from JavaFX. macOS uses {@code open}
 * and is not tested.
 */
public final class LocalFileOpener {

    public record Launch(List<String> command, boolean waitForExit) {
    }

    private LocalFileOpener() {
    }

    public static void open(Path target) throws IOException {
        Path absolute = target.toAbsolutePath().normalize();
        if (!Files.exists(absolute)) {
            throw new IOException("Nothing to open at " + absolute);
        }
        Launch launch = launchFor(System.getProperty("os.name", ""), absolute);
        try {
            Process process = new ProcessBuilder(launch.command()).start();
            if (!launch.waitForExit()) {
                return;
            }
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("Timed out opening " + absolute);
            }
            if (process.exitValue() != 0) {
                throw new IOException("Could not open " + absolute);
            }
        } catch (IOException failure) {
            if (desktopOpen(absolute)) {
                return;
            }
            throw failure.getMessage() == null
                    ? new IOException("Could not open " + absolute, failure)
                    : failure;
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while opening " + absolute, failure);
        }
    }

    static Launch launchFor(String osName, Path absolute) {
        String os = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        String path = absolute.toString();
        if (os.contains("win")) {
            return new Launch(List.of("explorer.exe", path), false);
        }
        if (os.contains("mac")) {
            return new Launch(List.of("open", path), true);
        }
        return new Launch(List.of("xdg-open", path), true);
    }

    private static boolean desktopOpen(Path absolute) {
        try {
            if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                return false;
            }
            Desktop.getDesktop().open(absolute.toFile());
            return true;
        } catch (IOException | UnsupportedOperationException | SecurityException failure) {
            return false;
        }
    }
}
