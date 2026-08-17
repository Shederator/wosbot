package dev.frostguard.app.bootstrap;

import dev.frostguard.api.runtime.RuntimeChannel;
import dev.frostguard.api.runtime.WorkspacePaths;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.prefs.BackingStoreException;

public final class WorkspaceSettingsSnapshot {
    private static final String COMPLETED_FILE = "channel-onboarding.properties";
    private static final String JOURNAL_FILE = "channel-onboarding.in-progress";
    private static final List<String> DATABASE_SUFFIXES = List.of("", "-wal", "-shm");

    private final WorkspacePaths source;
    private final WorkspacePaths target;

    public WorkspaceSettingsSnapshot(WorkspacePaths source, WorkspacePaths target) {
        if (source.channel() != RuntimeChannel.STABLE || target.channel() != RuntimeChannel.NIGHTLY) {
            throw new IllegalArgumentException("Settings snapshots are supported only from Stable to Nightly");
        }
        this.source = source;
        this.target = target;
    }

    public static Optional<WorkspaceSettingsSnapshot> forCurrentNightly() {
        WorkspacePaths current = WorkspacePaths.current();
        if (current.channel() != RuntimeChannel.NIGHTLY) {
            return Optional.empty();
        }
        return current.releasePeer(RuntimeChannel.STABLE)
                .map(stable -> new WorkspaceSettingsSnapshot(stable, current));
    }

    public boolean isCompleted() {
        return Files.isRegularFile(completedMarker());
    }

    public boolean hasStableSettings() {
        return Files.isRegularFile(source.marker()) && Files.isRegularFile(source.database());
    }

    public boolean isTargetFresh() throws IOException {
        recoverInterruptedCopy();
        return !isCompleted()
                && databaseFiles(target).stream().noneMatch(Files::exists)
                && directoryIsEmpty(target.config())
                && directoryIsEmpty(target.customTasks())
                && !Files.exists(target.watcherConfig());
    }

    public boolean sourceIsAvailable() throws IOException {
        if (!hasStableSettings()) {
            return false;
        }
        try (SourceLocks ignored = SourceLocks.acquire(source)) {
            return true;
        } catch (WorkspaceBusyException busy) {
            return false;
        }
    }

    public void startFresh() throws IOException {
        try {
            WorkspacePreferences.removeAll(target);
        } catch (BackingStoreException cause) {
            throw new IOException("Could not reset Nightly desktop preferences", cause);
        }
        Files.writeString(completedMarker(), "mode=fresh\nsource=none\n",
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    public void copyFromStable() throws IOException {
        if (!isTargetFresh()) {
            throw new IOException("Nightly settings already exist; first-run copy is no longer safe");
        }
        if (!hasStableSettings()) {
            throw new IOException("No Stable settings are available to copy");
        }

        Path staging = target.cache().resolve("settings-import-" + UUID.randomUUID());
        try (SourceLocks ignored = SourceLocks.acquire(source)) {
            Files.createDirectories(staging);
            stageFiles(staging);
            Files.writeString(journal(), "source=" + source.root() + "\n",
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            promote(staging);
            try {
                WorkspacePreferences.copyAll(source, target);
            } catch (BackingStoreException cause) {
                throw new IOException("Could not copy desktop preferences", cause);
            }
            Files.deleteIfExists(journal());
            Files.writeString(completedMarker(), "mode=stable-snapshot\nsource=" + source.root() + "\n",
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (Exception failure) {
            rollbackImportedFiles();
            if (failure instanceof IOException ioFailure) {
                throw ioFailure;
            }
            throw new IOException("Could not copy Stable settings", failure);
        } finally {
            deleteTree(staging);
        }
    }

    private void stageFiles(Path staging) throws IOException {
        for (Path databaseFile : databaseFiles(source)) {
            if (Files.isRegularFile(databaseFile)) {
                Files.copy(databaseFile, staging.resolve(databaseFile.getFileName()),
                        StandardCopyOption.COPY_ATTRIBUTES);
            }
        }
        copyDirectory(source.config(), staging.resolve("config"));
        copyDirectory(source.customTasks(), staging.resolve("custom-tasks"));
        if (Files.isRegularFile(source.watcherConfig())) {
            Files.createDirectories(staging.resolve("watcher"));
            Files.copy(source.watcherConfig(), staging.resolve("watcher").resolve(
                    source.watcherConfig().getFileName()), StandardCopyOption.COPY_ATTRIBUTES);
        }
    }

    private void promote(Path staging) throws IOException {
        for (Path stagedDatabase : databaseFiles(new WorkspacePaths(staging, RuntimeChannel.STABLE))) {
            if (Files.isRegularFile(stagedDatabase)) {
                Files.move(stagedDatabase, target.root().resolve(stagedDatabase.getFileName()),
                        StandardCopyOption.ATOMIC_MOVE);
            }
        }
        moveDirectoryContents(staging.resolve("config"), target.config());
        moveDirectoryContents(staging.resolve("custom-tasks"), target.customTasks());
        Path watcherConfig = staging.resolve("watcher").resolve(source.watcherConfig().getFileName());
        if (Files.isRegularFile(watcherConfig)) {
            Files.move(watcherConfig, target.watcherConfig(), StandardCopyOption.ATOMIC_MOVE);
        }
    }

    private void recoverInterruptedCopy() throws IOException {
        if (Files.exists(journal()) && !isCompleted()) {
            rollbackImportedFiles();
        }
    }

    private void rollbackImportedFiles() throws IOException {
        for (Path databaseFile : databaseFiles(target)) {
            Files.deleteIfExists(databaseFile);
        }
        clearDirectory(target.config());
        clearDirectory(target.customTasks());
        Files.deleteIfExists(target.watcherConfig());
        try {
            WorkspacePreferences.removeAll(target);
        } catch (BackingStoreException cause) {
            throw new IOException("Could not roll back desktop preferences", cause);
        }
        Files.deleteIfExists(journal());
        Files.deleteIfExists(completedMarker());
    }

    private Path completedMarker() {
        return target.root().resolve(COMPLETED_FILE);
    }

    private Path journal() {
        return target.root().resolve(JOURNAL_FILE);
    }

    private static List<Path> databaseFiles(WorkspacePaths workspace) {
        List<Path> files = new ArrayList<>();
        for (String suffix : DATABASE_SUFFIXES) {
            files.add(Path.of(workspace.database().toString() + suffix));
        }
        return files;
    }

    private static boolean directoryIsEmpty(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return true;
        }
        try (var children = Files.list(directory)) {
            return children.findAny().isEmpty();
        }
    }

    private static void copyDirectory(Path source, Path destination) throws IOException {
        if (!Files.isDirectory(source)) {
            return;
        }
        try (var paths = Files.walk(source)) {
            for (Path path : paths.toList()) {
                Path relative = source.relativize(path);
                Path copy = destination.resolve(relative);
                if (Files.isDirectory(path)) {
                    Files.createDirectories(copy);
                } else {
                    Files.copy(path, copy, StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        }
    }

    private static void moveDirectoryContents(Path source, Path destination) throws IOException {
        if (!Files.isDirectory(source)) {
            return;
        }
        try (var children = Files.list(source)) {
            for (Path child : children.toList()) {
                Files.move(child, destination.resolve(child.getFileName()), StandardCopyOption.ATOMIC_MOVE);
            }
        }
    }

    private static void clearDirectory(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return;
        }
        try (var children = Files.list(directory)) {
            for (Path child : children.toList()) {
                deleteTree(child);
            }
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static final class SourceLocks implements AutoCloseable {
        private final LockedFile application;
        private final LockedFile watcher;

        private SourceLocks(LockedFile application, LockedFile watcher) {
            this.application = application;
            this.watcher = watcher;
        }

        static SourceLocks acquire(WorkspacePaths source) throws IOException {
            LockedFile application = LockedFile.acquire(source.applicationLock());
            try {
                return new SourceLocks(application, LockedFile.acquire(source.watcherLock()));
            } catch (Exception failure) {
                application.close();
                throw failure;
            }
        }

        @Override
        public void close() {
            watcher.close();
            application.close();
        }
    }

    private static final class LockedFile implements AutoCloseable {
        private final FileChannel channel;
        private final FileLock lock;

        private LockedFile(FileChannel channel, FileLock lock) {
            this.channel = channel;
            this.lock = lock;
        }

        static LockedFile acquire(Path path) throws IOException {
            Files.createDirectories(path.getParent());
            FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            try {
                FileLock lock;
                try {
                    lock = channel.tryLock();
                } catch (OverlappingFileLockException busy) {
                    lock = null;
                }
                if (lock == null) {
                    throw new WorkspaceBusyException();
                }
                return new LockedFile(channel, lock);
            } catch (Exception failure) {
                channel.close();
                throw failure;
            }
        }

        @Override
        public void close() {
            try {
                lock.release();
            } catch (IOException ignored) {
            }
            try {
                channel.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static final class WorkspaceBusyException extends IOException {
    }
}
