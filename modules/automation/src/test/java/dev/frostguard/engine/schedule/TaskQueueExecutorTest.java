package dev.frostguard.engine.schedule;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

import org.junit.jupiter.api.Test;

class TaskQueueExecutorTest {

    @Test
    void interruptibleWorkerTerminatesBeforeStopCompletes() throws Exception {
        TaskQueueExecutor executor = new TaskQueueExecutor();
        CountDownLatch started = new CountDownLatch(1);

        executor.start(() -> {
            started.countDown();
            try {
                Thread.sleep(Duration.ofMinutes(1));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }, "cooperative-worker");
        assertTrue(started.await(1, TimeUnit.SECONDS));

        executor.interrupt();
        TaskQueueExecutor.AwaitResult result = executor.awaitTermination(Duration.ofSeconds(1));

        assertTrue(result.terminated());
        assertFalse(executor.isAlive());
    }

    @Test
    void timedOutWorkerRemainsTrackedAndBlocksReplacement() throws Exception {
        TaskQueueExecutor executor = new TaskQueueExecutor();
        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean release = new AtomicBoolean();

        executor.start(() -> {
            started.countDown();
            while (!release.get()) {
                Thread.interrupted();
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
            }
        }, "non-cooperative-worker");
        assertTrue(started.await(1, TimeUnit.SECONDS));

        try {
            executor.interrupt();
            assertFalse(executor.awaitTermination(Duration.ZERO).terminated());
            TaskQueueExecutor.AwaitResult timedOut = executor.awaitTermination(Duration.ofMillis(25));

            assertFalse(timedOut.terminated());
            assertTrue(executor.isAlive());
            assertThrows(IllegalStateException.class,
                    () -> executor.start(() -> { }, "replacement-worker"));
        } finally {
            release.set(true);
        }

        assertTrue(executor.awaitTermination(Duration.ofSeconds(1)).terminated());
        assertFalse(executor.isAlive());
    }
}
