package dev.frostguard.engine.diagnostics;

import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.api.runtime.WorkspacePaths;
import dev.frostguard.vision.convert.ImageConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Writes local diagnostic PNGs under {@code logs/snapshot/<activity>}.
 * Each activity has its own directory. The filename carries a UTC timestamp
 * and a failure type. Names never include profile names, device serials, or
 * exception text. Callers anonymize a frame before sharing it; this store
 * does not redact or upload.
 */
public final class DiagnosticSnapshotStore {

    public static final int MAX_RETAINED_CAPTURES_PER_ACTIVITY = 20;
    static final String DESKTOP_ACTIVITY = "desktop";

    private static final Logger logger = LoggerFactory.getLogger(DiagnosticSnapshotStore.class);
    static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss.SSS'Z'")
            .withZone(ZoneOffset.UTC);
    private static final Object WRITE_LOCK = new Object();
    private static final Pattern FLAT_CAPTURE = Pattern.compile(
            "^(\\d{8}T\\d{6}\\.\\d{3}Z)-([a-z0-9]+)-(.+)\\.png$");

    private final Path workspaceRoot;
    private final BooleanSupplier diagnosticSnapshotsEnabled;
    private final BooleanSupplier desktopSnapshotsEnabled;
    private final DesktopFrameSource desktopFrames;

    public DiagnosticSnapshotStore(Path workspaceRoot) {
        this(workspaceRoot, () -> true, () -> false, () -> Optional.empty());
    }

    DiagnosticSnapshotStore(
            Path workspaceRoot,
            BooleanSupplier desktopSnapshotsEnabled,
            DesktopFrameSource desktopFrames) {
        this(workspaceRoot, () -> true, desktopSnapshotsEnabled, desktopFrames);
    }

    private DiagnosticSnapshotStore(
            Path workspaceRoot,
            BooleanSupplier diagnosticSnapshotsEnabled,
            BooleanSupplier desktopSnapshotsEnabled,
            DesktopFrameSource desktopFrames) {
        this.workspaceRoot = workspaceRoot.toAbsolutePath().normalize();
        this.diagnosticSnapshotsEnabled = diagnosticSnapshotsEnabled;
        this.desktopSnapshotsEnabled = desktopSnapshotsEnabled;
        this.desktopFrames = desktopFrames;
    }

    public static DiagnosticSnapshotStore forCurrentWorkspace() {
        return new DiagnosticSnapshotStore(
                WorkspacePaths.current().root(),
                MissingTemplateSnapshotSettings::enabled,
                () -> MissingTemplateSnapshotSettings.enabled() && DesktopSnapshotSettings.enabled(),
                DesktopFrames.platform());
    }

    public Path directory() {
        return workspaceRoot.resolve("logs").resolve("snapshot");
    }

    /**
     * Saves one PNG and returns its workspace-relative path using {@code /}
     * separators. {@code activity} names the calling task and becomes the
     * directory under {@code logs/snapshot}. {@code type} names the situation.
     * An unusable frame or any filesystem failure returns empty and leaves no
     * partial file. Retention then keeps the newest captures in that directory
     * and leaves every other activity untouched.
     */
    public Optional<String> write(RawImageData frame, String activity, String type, Instant capturedAt) {
        return write(frame, activity, type, capturedAt, true);
    }

    /** Saves an emulator frame when the caller captures its desktop frame separately. */
    public Optional<String> writeWithoutDesktop(
            RawImageData frame, String activity, String type, Instant capturedAt) {
        return write(frame, activity, type, capturedAt, false);
    }

    private Optional<String> write(
            RawImageData frame, String activity, String type, Instant capturedAt, boolean pairDesktop) {
        if (capturedAt == null || !isEnabled()) {
            return Optional.empty();
        }
        BufferedImage image;
        try {
            image = ImageConverter.toBufferedImage(frame);
        } catch (RuntimeException failure) {
            return Optional.empty();
        }
        Optional<String> saved = persist(image, activity, type, capturedAt);
        if (pairDesktop && saved.isPresent() && !DESKTOP_ACTIVITY.equals(activityToken(activity))) {
            accompanyDesktop(type, capturedAt);
        }
        return saved;
    }

    /**
     * Saves one desktop frame when the global setting is on. The returned path
     * uses the {@code desktop} activity so it does not consume another task's quota.
     */
    public Optional<String> captureDesktop(String type, Instant capturedAt) {
        if (!desktopEnabled()) {
            return Optional.empty();
        }
        try {
            Optional<BufferedImage> image = desktopFrames.capture();
            if (image.isEmpty()) {
                return Optional.empty();
            }
            Optional<String> saved = persist(image.get(), DESKTOP_ACTIVITY, type, capturedAt);
            if (saved.isEmpty()) {
                logger.warn("Desktop snapshot was captured but not saved.");
            }
            return saved;
        } catch (RuntimeException failure) {
            logger.warn("Desktop snapshot was not saved: {}", failure.toString());
            return Optional.empty();
        }
    }

    private void accompanyDesktop(String type, Instant capturedAt) {
        if (!desktopEnabled()) {
            return;
        }
        captureDesktop(type, capturedAt);
    }

    public boolean isEnabled() {
        try {
            return diagnosticSnapshotsEnabled.getAsBoolean();
        } catch (RuntimeException failure) {
            logger.warn("Diagnostic snapshot setting could not be read: {}", failure.toString());
            return false;
        }
    }

    private boolean desktopEnabled() {
        try {
            return desktopSnapshotsEnabled.getAsBoolean();
        } catch (RuntimeException failure) {
            logger.warn("Desktop snapshot setting could not be read: {}", failure.toString());
            return false;
        }
    }

    private Optional<String> persist(BufferedImage image, String activity, String type, Instant capturedAt) {
        String activityKey = activityToken(activity);
        Path partial = null;
        try {
            synchronized (WRITE_LOCK) {
                Path snapshotDirectory = directory();
                Files.createDirectories(snapshotDirectory);
                migrateFlatCaptures(snapshotDirectory);
                Path activityDirectory = directory().resolve(activityKey);
                Files.createDirectories(activityDirectory);
                Path target = reserve(activityDirectory, fileName(type, capturedAt));
                partial = activityDirectory.resolve(target.getFileName().toString() + ".partial");
                if (!ImageIO.write(image, "png", partial.toFile())) {
                    Files.deleteIfExists(partial);
                    return Optional.empty();
                }
                moveIntoPlace(partial, target);
                partial = null;
                try {
                    pruneActivity(activityDirectory);
                } catch (IOException retentionFailure) {
                    // The saved capture stays referenceable when retention cannot run.
                }
                return Optional.of(relativePath(activityKey, target.getFileName().toString()));
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

    static String fileName(String type, Instant capturedAt) {
        return TIMESTAMP.format(capturedAt) + "-" + token(type) + ".png";
    }

    /**
     * Activity is one alphanumeric directory name. {@code bear} and
     * {@code beartrap} stay separate directories, so one retention pass
     * cannot delete the other.
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

    static String relativePath(String activity, String fileName) {
        return "logs/snapshot/" + activityToken(activity) + "/" + fileName;
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

    /**
     * Older builds wrote every capture in {@code logs/snapshot}. The activity
     * token is the single alphanumeric segment after the timestamp, so those
     * files can move into the directory of that routine without a second pass
     * by the operator.
     */
    private static void migrateFlatCaptures(Path snapshotRoot) throws IOException {
        List<Path> files;
        try (Stream<Path> listed = Files.list(snapshotRoot)) {
            files = listed.filter(Files::isRegularFile).toList();
        }
        Set<String> touched = new HashSet<>();
        for (Path file : files) {
            Matcher matcher = FLAT_CAPTURE.matcher(file.getFileName().toString());
            if (!matcher.matches()) {
                continue;
            }
            String activity = matcher.group(2);
            String groupedName = matcher.group(1) + "-" + matcher.group(3) + ".png";
            Path activityDirectory = snapshotRoot.resolve(activity);
            Files.createDirectories(activityDirectory);
            moveIntoPlace(file, reserve(activityDirectory, groupedName));
            touched.add(activity);
        }
        for (String activity : touched) {
            pruneActivity(snapshotRoot.resolve(activity));
        }
    }

    private static void pruneActivity(Path directory) throws IOException {
        Pattern managedActivity = Pattern.compile("\\d{8}T\\d{6}\\.\\d{3}Z-.+\\.png");
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
