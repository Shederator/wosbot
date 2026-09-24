package dev.frostguard.engine.diagnostics;

import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.api.runtime.WorkspacePaths;
import dev.frostguard.vision.convert.ImageConverter;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Writes local diagnostic PNGs under {@code logs/snapshot}. Filenames carry a
 * UTC timestamp, the activity that captured the frame, and a failure type.
 * They never include profile names, device serials, or exception text.
 * Callers anonymize a frame before sharing it; this store does not redact or upload.
 */
public final class DiagnosticSnapshotStore {

    public static final int MAX_RETAINED_CAPTURES_PER_ACTIVITY = 20;

    static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss.SSS'Z'")
            .withZone(ZoneOffset.UTC);
    private static final Object WRITE_LOCK = new Object();

    private final Path workspaceRoot;

    public DiagnosticSnapshotStore(Path workspaceRoot) {
        this.workspaceRoot = workspaceRoot.toAbsolutePath().normalize();
    }

    public static DiagnosticSnapshotStore forCurrentWorkspace() {
        return new DiagnosticSnapshotStore(WorkspacePaths.current().root());
    }

    public Path directory() {
        return workspaceRoot.resolve("logs").resolve("snapshot");
    }

    /**
     * Saves one PNG and returns its workspace-relative path using {@code /}
     * separators. {@code activity} names the calling task and {@code type}
     * names the situation. An unusable frame or any filesystem failure returns
     * empty and leaves no partial file. Retention then keeps the newest
     * captures of that activity and leaves every other activity untouched.
     */
    public Optional<String> write(RawImageData frame, String activity, String type, Instant capturedAt) {
        if (capturedAt == null) {
            return Optional.empty();
        }
        String activityKey = activityToken(activity);
        Path partial = null;
        try {
            BufferedImage image = ImageConverter.toBufferedImage(frame);
            synchronized (WRITE_LOCK) {
                Path directory = directory();
                Files.createDirectories(directory);
                Path target = reserve(directory, fileName(activityKey, type, capturedAt));
                partial = directory.resolve(target.getFileName().toString() + ".partial");
                if (!ImageIO.write(image, "png", partial.toFile())) {
                    Files.deleteIfExists(partial);
                    return Optional.empty();
                }
                moveIntoPlace(partial, target);
                partial = null;
                try {
                    pruneActivity(directory, activityKey);
                } catch (IOException retentionFailure) {
                    // The saved capture stays referenceable when retention cannot run.
                }
                return Optional.of(relativePath(target.getFileName().toString()));
            }
        } catch (RuntimeException | IOException failure) {
            if (partial != null) {
                try {
                    Files.deleteIfExists(partial);
                } catch (IOException ignored) {
                    // The caller still receives an empty result and continues the cooldown.
                }
            }
            return Optional.empty();
        }
    }

    static String fileName(String activity, String type, Instant capturedAt) {
        return TIMESTAMP.format(capturedAt) + "-" + activityToken(activity) + "-" + token(type) + ".png";
    }

    /**
     * Activity is one alphanumeric segment. The timestamp is followed by that
     * segment, then the type, so retention can group files without confusing
     * {@code bear} and {@code beartrap}.
     */
    static String activityToken(String value) {
        String normalized = value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        if (normalized.isBlank()) {
            return "unknown";
        }
        return normalized.length() > 48 ? normalized.substring(0, 48) : normalized;
    }

    static String token(String value) {
        String normalized = value == null ? "" : value.toLowerCase(Locale.ROOT).trim();
        normalized = normalized.replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (normalized.isBlank()) {
            return "unknown";
        }
        if (normalized.length() > 48) {
            normalized = normalized.substring(0, 48).replaceAll("-+$", "");
        }
        return normalized.isBlank() ? "unknown" : normalized;
    }

    static String relativePath(String fileName) {
        return "logs/snapshot/" + fileName;
    }

    private static Path reserve(Path directory, String fileName) throws IOException {
        String base = fileName.endsWith(".png") ? fileName.substring(0, fileName.length() - 4) : fileName;
        Path candidate = directory.resolve(base + ".png");
        int suffix = 2;
        while (Files.exists(candidate)) {
            if (suffix > 1000) {
                throw new IOException("snapshot name collision limit");
            }
            candidate = directory.resolve(base + "-" + suffix + ".png");
            suffix++;
        }
        return candidate;
    }

    private static void moveIntoPlace(Path partial, Path target) throws IOException {
        try {
            Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(partial, target);
        }
    }

    private static void pruneActivity(Path directory, String activity) throws IOException {
        Pattern managedActivity = Pattern.compile(
                "\\d{8}T\\d{6}\\.\\d{3}Z-" + Pattern.quote(activity) + "-.+\\.png");
        List<Path> managed;
        try (Stream<Path> files = Files.list(directory)) {
            managed = files
                    .filter(Files::isRegularFile)
                    .filter(path -> managedActivity.matcher(path.getFileName().toString()).matches())
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        }
        int excess = managed.size() - MAX_RETAINED_CAPTURES_PER_ACTIVITY;
        for (int index = 0; index < excess; index++) {
            Files.deleteIfExists(managed.get(index));
        }
    }
}
