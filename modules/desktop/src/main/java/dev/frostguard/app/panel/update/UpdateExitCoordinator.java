package dev.frostguard.app.panel.update;

import dev.frostguard.update.InstallerHandoff;

final class UpdateExitCoordinator {
    private final ShutdownAction shutdown;
    private final Runnable successfulExit;
    private final Runnable failedExit;

    UpdateExitCoordinator(ShutdownAction shutdown, Runnable successfulExit, Runnable failedExit) {
        this.shutdown = shutdown;
        this.successfulExit = successfulExit;
        this.failedExit = failedExit;
    }

    void execute(InstallerHandoff.HandoffSession session) throws Exception {
        try {
            shutdown.run();
            session.authorize();
        } catch (Exception exception) {
            session.cancel();
            failedExit.run();
            throw exception;
        }
        successfulExit.run();
    }

    @FunctionalInterface
    interface ShutdownAction {
        void run() throws Exception;
    }
}
