package dev.frostguard.engine.schedule;

import java.time.Instant;

/** Scheduler hook for cleanup only: no preparation, deployment, app restart or normal-task restore. */
public interface BearTerminalCleanup {
    /** True only after bounded, fresh-frame verification of a safe terminal UI. */
    boolean recoverTerminalUi(Instant eventEnd, Runnable authorizeInput);
}
