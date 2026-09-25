package dev.frostguard.engine.diagnostics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Asks the xdg-desktop-portal Screenshot interface over the session bus.
 * Robot is not used: on Wayland it records a black or partial XWayland image.
 * {@code interactive} stays false. A portal that still wants a dialog, or no
 * answer within a few seconds, yields no image and does not block the task.
 */
final class WaylandPortalScreenshot {

    private static final Logger logger = LoggerFactory.getLogger(WaylandPortalScreenshot.class);
    private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(5);
    private static final Pattern FILE_URI = Pattern.compile("file://[^\\s'<>]+");

    private WaylandPortalScreenshot() {
    }

    static Optional<BufferedImage> capture() {
        String token = "fg" + Long.toUnsignedString(System.nanoTime(), 36);
        Process started = null;
        ExecutorService readers = Executors.newSingleThreadExecutor();
        try {
            started = new ProcessBuilder(List.of(
                    "gdbus", "monitor", "--session", "--dest", "org.freedesktop.portal.Desktop"))
                    .redirectErrorStream(true)
                    .start();
            Process monitor = started;
            Future<Optional<Path>> response = readers.submit(() -> readResponse(monitor, token));
            Thread.sleep(200);
            if (!invokeScreenshot(token)) {
                return Optional.empty();
            }
            Optional<Path> imagePath = response.get(RESPONSE_TIMEOUT.toMillis() + 500, TimeUnit.MILLISECONDS);
            if (imagePath.isEmpty()) {
                logger.warn("Wayland desktop snapshot was not granted by the screenshot portal.");
                return Optional.empty();
            }
            BufferedImage image = ImageIO.read(imagePath.get().toFile());
            if (image == null) {
                logger.warn("Wayland desktop snapshot portal returned an unreadable image.");
                return Optional.empty();
            }
            return Optional.of(image);
        } catch (IOException | InterruptedException | java.util.concurrent.ExecutionException | RuntimeException failure) {
            if (failure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            logger.warn("Wayland desktop snapshot failed: {}", failure.toString());
            return Optional.empty();
        } catch (java.util.concurrent.TimeoutException failure) {
            logger.warn("Wayland desktop snapshot timed out waiting for the screenshot portal.");
            return Optional.empty();
        } finally {
            if (started != null) {
                started.destroyForcibly();
            }
            readers.shutdownNow();
        }
    }

    static List<String> screenshotCommand(String token) {
        return List.of(
                "gdbus", "call", "--session",
                "--dest", "org.freedesktop.portal.Desktop",
                "--object-path", "/org/freedesktop/portal/desktop",
                "--method", "org.freedesktop.portal.Screenshot.Screenshot",
                "''",
                "{'interactive': <false>, 'handle_token': <'" + token + "'>}");
    }

    static Optional<Path> imageFromResponse(String line, String token) {
        if (line == null || !line.contains(token) || !line.contains("Response") || !line.contains("uint32 0")) {
            return Optional.empty();
        }
        Matcher matcher = FILE_URI.matcher(line);
        if (!matcher.find()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Path.of(java.net.URI.create(matcher.group())));
        } catch (RuntimeException failure) {
            return Optional.empty();
        }
    }

    static boolean declined(String line, String token) {
        return line != null
                && line.contains(token)
                && line.contains("Response")
                && !line.toLowerCase(Locale.ROOT).contains("uint32 0");
    }

    private static boolean invokeScreenshot(String token) throws IOException, InterruptedException {
        Process call = new ProcessBuilder(screenshotCommand(token)).redirectErrorStream(true).start();
        boolean finished = call.waitFor(RESPONSE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        if (!finished || call.exitValue() != 0) {
            logger.warn("Wayland screenshot portal call did not succeed.");
            call.destroyForcibly();
            return false;
        }
        return true;
    }

    private static Optional<Path> readResponse(Process monitor, String token) throws IOException {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(monitor.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                Optional<Path> image = imageFromResponse(line, token);
                if (image.isPresent() || declined(line, token)) {
                    return image;
                }
            }
        }
        return Optional.empty();
    }
}
