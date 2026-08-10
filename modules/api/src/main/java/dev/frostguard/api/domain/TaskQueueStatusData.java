package dev.frostguard.api.domain;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * Comprehensive runtime report for a single profile's task execution queue.
 * Tracks operational flags (running, paused, reconnecting) and timing information
 * used by the scheduler to coordinate profile switching and idle management.
 */
public class TaskQueueStatusData {

    private volatile boolean operational;
    private volatile boolean halted;
    private volatile boolean haltedByUser;
    private volatile boolean reconnectionRequired;
    private volatile boolean reconnectionReady;
    private volatile boolean idleLimitBreached;
    private Integer idleCeilingMinutes;

    private volatile LocalDateTime haltedSince;
    private volatile LocalDateTime deferredUntil;
    private volatile LocalDateTime scheduledReconnection;

    private CycleMetrics cycleMetrics = new CycleMetrics();
    private volatile Thread reconnectionWorker;

    public TaskQueueStatusData() {
        this.operational = false;
        this.halted = false;
        this.haltedByUser = false;
        this.reconnectionRequired = false;
        this.reconnectionReady = false;
        this.haltedSince = LocalDateTime.MIN;
        this.deferredUntil = LocalDateTime.now();
        this.idleCeilingMinutes = 15;
    }

    public CycleMetrics getCycleMetrics() {
        return this.cycleMetrics;
    }

    public boolean isIdleLimitBreached() {
        return this.idleLimitBreached;
    }

    public void setIdleLimitBreached(boolean breached) {
        this.idleLimitBreached = breached;
    }

    public void setIdleCeilingMinutes(Integer minutes) {
        this.idleCeilingMinutes = minutes;
    }

    /**
     * Schedules a deferred reconnection after the specified minutes.
     */
    public void scheduleReconnection(long delayMinutes) {
        LocalDateTime target = LocalDateTime.now().plusMinutes(delayMinutes);
        this.setDeferredUntil(target);
        this.initiateReconnectionAt(target);
    }

    public void initiateReconnectionAt(LocalDateTime target) {
        this.halt();
        this.setReconnectionRequired(true);
        this.scheduledReconnection = target;
        this.reconnectionWorker = Thread.startVirtualThread(() -> {
            try {
                long waitMs = Duration.between(LocalDateTime.now(), target).toMillis();
                if (waitMs > 0) {
                    Thread.sleep(waitMs);
                    this.reconnectionReady = true;
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(ie);
            }
        });
    }

    public void cancelReconnectionWorker() {
        if (this.reconnectionWorker != null) {
            this.reconnectionWorker.interrupt();
        }
    }

    public LocalDateTime getScheduledReconnection() {
        return this.scheduledReconnection;
    }

    public void beginNewCycle() {
        this.cycleMetrics = new CycleMetrics();
    }

    public void resetAll() {
        this.operational = false;
        this.halted = false;
        this.haltedByUser = false;
        this.reconnectionRequired = false;
        this.reconnectionReady = false;
        this.idleLimitBreached = false;
        this.haltedSince = LocalDateTime.MIN;
        this.deferredUntil = LocalDateTime.now();
        this.cancelReconnectionWorker();
    }

    public boolean isHalted() {
        return this.halted;
    }

    public void halt() {
        this.setHalted(true);
    }

    public void setHalted(boolean halted) {
        this.halted = halted;
        if (halted) this.haltedSince = LocalDateTime.now();
    }

    public boolean isHaltedByUser() {
        return this.haltedByUser;
    }

    public void setHaltedByUser(boolean haltedByUser) {
        this.haltedByUser = haltedByUser;
    }

    public void userHalt() {
        this.setHalted(true);
        this.haltedByUser = true;
    }

    public boolean isReconnectionRequired() {
        return this.reconnectionRequired;
    }

    public void setReconnectionRequired(boolean required) {
        this.reconnectionRequired = required;
    }

    public LocalDateTime getHaltedSince() {
        return this.haltedSince;
    }

    public LocalDateTime getDeferredUntil() {
        return this.deferredUntil;
    }

    public void setDeferredUntil(long seconds) {
        this.deferredUntil = LocalDateTime.now().plusSeconds(seconds);
    }

    public boolean evaluateIdleBreach() {
        return LocalDateTime.now().plusMinutes(this.idleCeilingMinutes)
                .isBefore(this.getDeferredUntil());
    }

    public void setDeferredUntil(LocalDateTime time) {
        this.deferredUntil = time;
    }

    public boolean isOperational() {
        return this.operational;
    }

    public void setOperational(boolean operational) {
        this.operational = operational;
    }

    public boolean isReconnectionReady() {
        return this.reconnectionReady;
    }

    // Legacy compatibility
    public boolean isRunning() { return operational; }
    public void setRunning(boolean r) { this.operational = r; }
    public boolean isPaused() { return halted; }
    public void setPaused(boolean p) { setHalted(p); }
    public boolean isUserPaused() { return haltedByUser; }
    public void setUserPaused(boolean u) { this.haltedByUser = u; }
    public void pause() { halt(); }
    public void userPause() { userHalt(); }
    public boolean needsReconnect() { return reconnectionRequired; }
    public void setNeedsReconnect(boolean n) { setReconnectionRequired(n); }
    public boolean isReadyToReconnect() { return reconnectionReady; }
    public LocalDateTime getPausedAt() { return haltedSince; }
    public LocalDateTime getDelayUntil() { return deferredUntil; }
    public void setDelayUntil(long s) { setDeferredUntil(s); }
    public void setDelayUntil(LocalDateTime t) { this.deferredUntil = t; }
    public boolean checkIdleTimeExceeded() { return evaluateIdleBreach(); }
    public boolean isIdleTimeExceeded() { return idleLimitBreached; }
    public void setIdleTimeExceeded(boolean e) { this.idleLimitBreached = e; }
    public void setIdleTimeLimit(Integer l) { this.idleCeilingMinutes = l; }
    public void loopStarted() { beginNewCycle(); }
    public void setReconnectAt(long min) { scheduleReconnection(min); }
    public void setReconnectAt(LocalDateTime t) { initiateReconnectionAt(t); }
    public void cancelReconnectThread() { cancelReconnectionWorker(); }
    public LocalDateTime getReconnectAt() { return scheduledReconnection; }
    public CycleMetrics getLoopState() { return cycleMetrics; }
    public void reset() { resetAll(); }

    /**
     * Tracks timing and task-execution state for one scheduler iteration cycle.
     */
    public static class CycleMetrics {

        private final long cycleStartMs;
        private long cycleEndMs;
        private boolean routineExecuted = false;

        public CycleMetrics() {
            this.cycleStartMs = System.currentTimeMillis();
        }

        public void finishCycle() {
            this.cycleEndMs = System.currentTimeMillis();
        }

        public long elapsedMs() {
            if (this.cycleEndMs == 0) {
                return System.currentTimeMillis() - this.cycleStartMs;
            }
            return this.cycleEndMs - this.cycleStartMs;
        }

        public boolean wasRoutineExecuted() {
            return this.routineExecuted;
        }

        public void setRoutineExecuted(boolean executed) {
            this.routineExecuted = executed;
        }

        // Legacy compatibility
        public void endLoop() { finishCycle(); }
        public long getDuration() { return elapsedMs(); }
        public boolean isExecutedTask() { return routineExecuted; }
        public void setExecutedTask(boolean e) { this.routineExecuted = e; }
    }
}
