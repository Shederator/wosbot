package dev.frostguard.engine.schedule;

import java.time.Duration;
import java.util.Objects;

/**
 * Owns the lifecycle of one queue worker without losing a timed-out thread.
 */
final class TaskQueueExecutor {

    enum Termination {
        TERMINATED,
        TIMED_OUT,
        CALLER_INTERRUPTED
    }

    record AwaitResult(Termination termination, long elapsedMillis) {
        boolean terminated() {
            return termination == Termination.TERMINATED;
        }
    }

    private volatile Thread worker;

    synchronized void start(Runnable runnable, String name) {
        Objects.requireNonNull(runnable, "runnable");
        Thread previous = worker;
        if (previous != null && previous.isAlive()) {
            throw new IllegalStateException("Previous queue worker is still running: " + previous.getName());
        }

        Thread next = Thread.ofVirtual().unstarted(runnable);
        next.setName(name);
        worker = next;
        next.start();
    }

    void interrupt() {
        Thread snapshot = worker;
        if (snapshot != null) {
            snapshot.interrupt();
        }
    }

    AwaitResult awaitTermination(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must not be negative");
        }

        Thread snapshot = worker;
        if (snapshot == null || !snapshot.isAlive()) {
            clearTerminated(snapshot);
            return new AwaitResult(Termination.TERMINATED, 0L);
        }
        if (timeout.isZero()) {
            return new AwaitResult(Termination.TIMED_OUT, 0L);
        }

        long startedAt = System.nanoTime();
        try {
            snapshot.join(Math.max(1L, timeout.toMillis()));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return new AwaitResult(Termination.CALLER_INTERRUPTED, elapsedMillis(startedAt));
        }

        if (snapshot.isAlive()) {
            return new AwaitResult(Termination.TIMED_OUT, elapsedMillis(startedAt));
        }
        clearTerminated(snapshot);
        return new AwaitResult(Termination.TERMINATED, elapsedMillis(startedAt));
    }

    boolean isAlive() {
        Thread snapshot = worker;
        return snapshot != null && snapshot.isAlive();
    }

    private void clearTerminated(Thread expected) {
        if (worker == expected && (expected == null || !expected.isAlive())) {
            worker = null;
        }
    }

    private static long elapsedMillis(long startedAt) {
        return Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
    }
}
