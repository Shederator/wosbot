package dev.frostguard.update;

import java.nio.file.Path;

public interface InstallerHandoff {
    HandoffSession stage(Path installer, long parentPid, Path restartLauncher, Path workspaceRoot)
            throws UpdateException;

    interface HandoffSession {
        void authorize() throws UpdateException;
        void cancel();
    }
}
