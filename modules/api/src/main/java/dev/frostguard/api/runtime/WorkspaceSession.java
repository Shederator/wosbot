package dev.frostguard.api.runtime;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;

public final class WorkspaceSession implements AutoCloseable {
    private final WorkspacePaths paths;
    private final FileChannel lockChannel;
    private final FileLock lock;

    private WorkspaceSession(WorkspacePaths paths, FileChannel lockChannel, FileLock lock) {
        this.paths = paths;
        this.lockChannel = lockChannel;
        this.lock = lock;
    }

    public static WorkspaceSession open(WorkspacePaths paths) {
        initializeLayout(paths);
        try {
            FileChannel channel = FileChannel.open(paths.applicationLock(),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            FileLock lock;
            try {
                lock = channel.tryLock();
            } catch (OverlappingFileLockException alreadyLocked) {
                lock = null;
            }
            if (lock == null) {
                channel.close();
                throw new WorkspaceInUseException(paths);
            }
            System.setProperty(WorkspacePaths.WORKSPACE_PROPERTY, paths.root().toString());
            System.setProperty("frostguard.log.dir", paths.logs().toString());
            return new WorkspaceSession(paths, channel, lock);
        } catch (IOException cause) {
            throw new IllegalStateException("Could not initialize Frostguard workspace: " + paths.root(), cause);
        }
    }

    public static void initializeLayout(WorkspacePaths paths) {
        try {
            createLayout(paths);
        } catch (IOException cause) {
            throw new IllegalStateException("Could not initialize Frostguard workspace: " + paths.root(), cause);
        }
    }

    private static void createLayout(WorkspacePaths paths) throws IOException {
        Files.createDirectories(paths.root());
        Files.createDirectories(paths.config());
        Files.createDirectories(paths.logs());
        Files.createDirectories(paths.customTasks());
        Files.createDirectories(paths.cache());
        Files.createDirectories(paths.watcher());
        if (Files.exists(paths.marker())) {
            String marker = Files.readString(paths.marker(), StandardCharsets.UTF_8);
            String expectedChannel = "\"channel\": \"" + paths.channel().directoryName() + "\"";
            if (!marker.contains(expectedChannel)) {
                throw new IllegalStateException("Workspace channel does not match "
                        + paths.channel().directoryName() + ": " + paths.root());
            }
        } else {
            String name = paths.root().getFileName().toString().replace("\\", "\\\\").replace("\"", "\\\"");
            String json = "{\n"
                    + "  \"schemaVersion\": 1,\n"
                    + "  \"name\": \"" + name + "\",\n"
                    + "  \"channel\": \"" + paths.channel().directoryName() + "\"\n"
                    + "}\n";
            Files.writeString(paths.marker(), json, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        }
    }

    public WorkspacePaths paths() {
        return paths;
    }

    @Override
    public void close() {
        try {
            lock.release();
        } catch (IOException ignored) {
        }
        try {
            lockChannel.close();
        } catch (IOException ignored) {
        }
    }

    public static final class WorkspaceInUseException extends IllegalStateException {
        private final WorkspacePaths paths;

        private WorkspaceInUseException(WorkspacePaths paths) {
            super("Frostguard workspace is already in use: " + paths.root());
            this.paths = paths;
        }

        public WorkspacePaths paths() {
            return paths;
        }
    }
}
