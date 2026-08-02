package dev.frostguard.data.migration;

import dev.frostguard.api.runtime.FrostguardPaths;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

/** Performs the one-time, backup-first migration from pre-distribution data locations. */
public final class LegacyDataMigrator {
    public static final String SOURCE_PROPERTY = "frostguard.migrate.from";
    private static final String MARKER = "legacy-migration.properties";
    private static final List<String> DATABASE_FILES = List.of("database.db", "database.db-wal", "database.db-shm");
    private static final DateTimeFormatter BACKUP_TIME =
            DateTimeFormatter.ofPattern("uuuuMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private LegacyDataMigrator() {
    }

    public static MigrationResult migrate(FrostguardPaths paths) {
        return migrate(paths, Clock.systemUTC());
    }

    static MigrationResult migrate(FrostguardPaths paths, Clock clock) {
        Path marker = paths.dataHome().resolve("config").resolve(MARKER);
        if (Files.exists(marker) || Files.exists(paths.dataHome().resolve("frostguard.db"))) {
            return MigrationResult.notRequired();
        }

        List<Path> candidates = candidates(paths).stream()
                .filter(LegacyDataMigrator::containsLegacyData)
                .toList();
        if (candidates.isEmpty()) {
            return MigrationResult.notRequired();
        }

        Path source = selectSource(candidates);
        paths.createDataDirectories();
        Path backup = paths.dataHome().resolve("migration-backups")
                .resolve(BACKUP_TIME.format(Instant.now(clock)));
        try {
            verifyDatabaseUnlocked(source);
            Files.createDirectories(backup);
            copyLegacyFiles(source, backup, true);
            copyDatabaseGroup(source, paths.dataHome());
            copyDirectoryIfPresent(source.resolve("custom_tasks"), paths.customTasks());
            copyDirectoryIfPresent(source.resolve("log"), paths.logs());
            copyDirectoryIfPresent(source.resolve("logs"), paths.logs());
            copyDirectoryIfPresent(source.resolve("temp"), paths.temp());
            writeMarker(marker, source, backup, clock);
            return new MigrationResult(true, source, backup);
        } catch (IOException cause) {
            throw new IllegalStateException("Legacy migration from " + source + " failed; backup location: " + backup, cause);
        }
    }

    private static Set<Path> candidates(FrostguardPaths paths) {
        Set<Path> result = new LinkedHashSet<>();
        Path appHome = paths.applicationHome();
        result.add(appHome);
        Path parent = appHome.getParent();
        if (appHome.getFileName() != null && ".frostguard".equals(appHome.getFileName().toString()) && parent != null) {
            result.add(parent);
            result.add(parent.resolve("fg-app/target"));
        } else {
            result.add(appHome.resolve("app"));
        }
        Path codeParent = Files.isDirectory(paths.codeSource()) ? paths.codeSource() : paths.codeSource().getParent();
        if (codeParent != null) result.add(codeParent);
        return result.stream().map(path -> path.toAbsolutePath().normalize())
                .filter(path -> !path.equals(paths.dataHome()))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static boolean containsLegacyData(Path root) {
        if (DATABASE_FILES.stream().map(root::resolve).anyMatch(Files::exists)) return true;
        return hasFiles(root.resolve("custom_tasks")) || hasFiles(root.resolve("log"))
                || hasFiles(root.resolve("logs")) || hasFiles(root.resolve("temp"));
    }

    private static boolean hasFiles(Path directory) {
        if (!Files.isDirectory(directory)) return false;
        try (var entries = Files.walk(directory)) {
            return entries.anyMatch(Files::isRegularFile);
        } catch (IOException ignored) {
            return true;
        }
    }

    private static Path selectSource(List<Path> candidates) {
        String selected = System.getProperty(SOURCE_PROPERTY);
        if (selected != null && !selected.isBlank()) {
            Path requested = Path.of(selected).toAbsolutePath().normalize();
            if (!candidates.contains(requested)) {
                throw new IllegalStateException("Selected legacy source " + requested
                        + " is not one of the detected candidates: " + candidates);
            }
            return requested;
        }
        if (candidates.size() > 1) {
            throw new IllegalStateException("Multiple legacy Frostguard data locations were detected: "
                    + candidates + ". Restart with -D" + SOURCE_PROPERTY + "=/exact/source/path; no files were changed.");
        }
        return candidates.getFirst();
    }

    private static void verifyDatabaseUnlocked(Path source) throws IOException {
        List<FileChannel> channels = new ArrayList<>();
        List<FileLock> locks = new ArrayList<>();
        try {
            for (String name : DATABASE_FILES) {
                Path file = source.resolve(name);
                if (!Files.exists(file)) continue;
                FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE);
                channels.add(channel);
                FileLock lock = channel.tryLock();
                if (lock == null) throw new IOException("Legacy SQLite file is locked: " + file);
                locks.add(lock);
            }
        } finally {
            for (FileLock lock : locks) lock.close();
            for (FileChannel channel : channels) channel.close();
        }
    }

    private static void copyLegacyFiles(Path source, Path backup, boolean includeDirectories) throws IOException {
        for (String name : DATABASE_FILES) copyFileIfPresent(source.resolve(name), backup.resolve(name));
        if (includeDirectories) {
            copyDirectoryIfPresent(source.resolve("custom_tasks"), backup.resolve("custom_tasks"));
            copyDirectoryIfPresent(source.resolve("log"), backup.resolve("log"));
            copyDirectoryIfPresent(source.resolve("logs"), backup.resolve("logs"));
            copyDirectoryIfPresent(source.resolve("temp"), backup.resolve("temp"));
        }
    }

    private static void copyDatabaseGroup(Path source, Path dataHome) throws IOException {
        copyFileIfPresent(source.resolve("database.db"), dataHome.resolve("frostguard.db"));
        copyFileIfPresent(source.resolve("database.db-wal"), dataHome.resolve("frostguard.db-wal"));
        copyFileIfPresent(source.resolve("database.db-shm"), dataHome.resolve("frostguard.db-shm"));
    }

    private static void copyFileIfPresent(Path source, Path target) throws IOException {
        if (!Files.isRegularFile(source)) return;
        Files.createDirectories(target.getParent());
        Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES);
    }

    private static void copyDirectoryIfPresent(Path source, Path target) throws IOException {
        if (!Files.isDirectory(source)) return;
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                Files.createDirectories(target.resolve(source.relativize(directory)));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Path destination = target.resolve(source.relativize(file));
                if (!Files.exists(destination)) Files.copy(file, destination, StandardCopyOption.COPY_ATTRIBUTES);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void writeMarker(Path marker, Path source, Path backup, Clock clock) throws IOException {
        Properties properties = new Properties();
        properties.setProperty("source", source.toString());
        properties.setProperty("backup", backup.toString());
        properties.setProperty("completedAt", Instant.now(clock).toString());
        Files.createDirectories(marker.getParent());
        try (var output = Files.newOutputStream(marker, StandardOpenOption.CREATE_NEW)) {
            properties.store(output, "Frostguard legacy migration");
        }
    }

    public record MigrationResult(boolean migrated, Path source, Path backup) {
        static MigrationResult notRequired() {
            return new MigrationResult(false, null, null);
        }
    }
}
