package dev.frostguard.tasks.diagnostics;

import dev.frostguard.engine.error.ADBConnectionException;
import dev.frostguard.engine.error.ProfileCooldownException;
import dev.frostguard.engine.error.ProfileInReconnectStateException;
import dev.frostguard.engine.error.StopExecutionException;
import dev.frostguard.engine.error.TaskPreemptedException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** Propagates scheduler and device-control failures that a domain retry must not swallow. */
public final class TaskControlSignals {

    private TaskControlSignals() {
    }

    public static void rethrowControlSignal(Throwable failure) {
        // Emulator capture can wrap an ADB failure in a plain RuntimeException.
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable cause = failure; cause != null && seen.add(cause); cause = cause.getCause()) {
            if (cause instanceof StopExecutionException stop) throw stop;
            if (cause instanceof TaskPreemptedException preempted) throw preempted;
            if (cause instanceof ProfileInReconnectStateException reconnect) throw reconnect;
            if (cause instanceof ProfileCooldownException cooldown) throw cooldown;
            if (cause instanceof ADBConnectionException adb) throw adb;
        }
        if (Thread.currentThread().isInterrupted()) throw StopExecutionException.userCancelled();
    }
}
