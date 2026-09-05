package dev.frostguard.engine.schedule;

import dev.frostguard.api.configs.ControlledExecutionCapability;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.TaskExecutionState;
import dev.frostguard.api.domain.TaskStepStatus;
import dev.frostguard.engine.error.StopExecutionException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskExecutionControlTest {

    @Test
    void initialPermitStartsPreparationAndCarriesIntoTheFirstStep() throws Exception {
        TaskExecutionControl control = control(ControlledExecutionCapability.STEP_AWARE);
        AtomicInteger executions = new AtomicInteger();
        CompletableFuture<Void> run = CompletableFuture.runAsync(() -> {
            control.awaitExecutionStart();
            control.runStep("First", executions::incrementAndGet);
            control.runStep("Second", executions::incrementAndGet);
        });

        Thread.sleep(25);
        assertEquals(TaskExecutionState.PAUSED, control.snapshot().state());
        assertEquals(0, executions.get());

        control.executeNextStep();
        await(() -> "Second".equals(control.snapshot().nextStep()));

        assertEquals(1, executions.get());
        assertEquals(TaskExecutionState.PAUSED, control.snapshot().state());
        assertNull(control.snapshot().currentStep());
        control.requestStop();
        assertThrows(Exception.class, () -> run.get(2, TimeUnit.SECONDS));
    }

    @Test
    void startsPausedAndExecutesExactlyOneStepPerPermit() throws Exception {
        TaskExecutionControl control = control(ControlledExecutionCapability.STEP_AWARE);
        AtomicInteger executions = new AtomicInteger();
        CompletableFuture<Void> run = CompletableFuture.runAsync(() -> {
            control.runStep("First", executions::incrementAndGet);
            control.runStep("Second", executions::incrementAndGet);
        });

        await(() -> "First".equals(control.snapshot().nextStep()));
        assertEquals(TaskExecutionState.PAUSED, control.snapshot().state());
        assertEquals(TaskStepStatus.WAITING, control.snapshot().nextStepStatus());
        assertNull(control.snapshot().currentStep());

        control.executeNextStep();
        await(() -> "Second".equals(control.snapshot().nextStep()));

        assertEquals(1, executions.get());
        assertEquals("First", control.snapshot().lastStep());
        assertEquals(TaskStepStatus.COMPLETED, control.snapshot().lastStepStatus());
        assertEquals(TaskExecutionState.PAUSED, control.snapshot().state());

        control.resume();
        run.get(2, TimeUnit.SECONDS);
        control.complete();

        assertEquals(2, executions.get());
        assertEquals(TaskExecutionState.COMPLETED, control.snapshot().state());
    }

    @Test
    void preservesStableStepIdAcrossWaitingRunningAndCompletion() throws Exception {
        TaskExecutionControl control = control(ControlledExecutionCapability.STEP_AWARE);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> run = CompletableFuture.runAsync(() ->
                control.runStep("stable-id", "Visible label", () -> {
                    started.countDown();
                    awaitLatch(release);
                }));

        await(() -> "stable-id".equals(control.snapshot().nextStepId()));
        assertEquals(TaskStepStatus.WAITING, control.snapshot().nextStepStatus());

        control.executeNextStep();
        assertTrue(started.await(2, TimeUnit.SECONDS));
        try {
            assertEquals("stable-id", control.snapshot().currentStepId());
            assertEquals(TaskStepStatus.STARTED, control.snapshot().currentStepStatus());
        } finally {
            release.countDown();
        }
        run.get(2, TimeUnit.SECONDS);

        assertEquals("stable-id", control.snapshot().lastStepId());
        assertEquals(TaskStepStatus.COMPLETED, control.snapshot().lastStepStatus());
        assertTrue(control.snapshot().history().stream()
                .allMatch(event -> "stable-id".equals(event.stepId())));
    }

    @Test
    void pauseRequestedDuringStepWaitsForTheNextBoundary() throws Exception {
        TaskExecutionControl control = control(ControlledExecutionCapability.STEP_AWARE);
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CompletableFuture<Void> run = CompletableFuture.runAsync(() -> {
            control.runStep("First", () -> {
                firstStarted.countDown();
                awaitLatch(releaseFirst);
            });
            control.runStep("Second", () -> { });
        });

        control.resume();
        assertTrue(firstStarted.await(2, TimeUnit.SECONDS));
        control.pause();
        assertEquals(TaskExecutionState.PAUSE_REQUESTED, control.snapshot().state());

        releaseFirst.countDown();
        await(() -> "Second".equals(control.snapshot().nextStep()));
        assertEquals(TaskExecutionState.PAUSED, control.snapshot().state());

        control.requestStop();
        assertThrows(Exception.class, () -> run.get(2, TimeUnit.SECONDS));
        control.stopped();
        assertEquals(TaskExecutionState.STOPPED, control.snapshot().state());
        assertEquals(TaskStepStatus.STOPPED, control.snapshot().lastStepStatus());
    }

    @Test
    void coarseCapabilityUsesOneWholeTaskBoundary() throws Exception {
        TaskExecutionControl control = control(ControlledExecutionCapability.COARSE);
        AtomicInteger taskExecutions = new AtomicInteger();
        CompletableFuture<Void> run = CompletableFuture.runAsync(() -> {
            control.awaitExecutionStart();
            control.runStep("Existing task", taskExecutions::incrementAndGet);
        });

        Thread.sleep(25);
        assertEquals(TaskExecutionState.PAUSED, control.snapshot().state());
        assertEquals(0, taskExecutions.get());

        control.executeNextStep();
        run.get(2, TimeUnit.SECONDS);
        control.complete();

        assertEquals(1, taskExecutions.get());
        assertEquals(TaskExecutionState.COMPLETED, control.snapshot().state());
        assertEquals(TaskStepStatus.COMPLETED, control.snapshot().lastStepStatus());
    }

    @Test
    void stopWhileWaitingCancelsWithoutExecutingTheStep() throws Exception {
        TaskExecutionControl control = control(ControlledExecutionCapability.STEP_AWARE);
        AtomicInteger executions = new AtomicInteger();
        CompletableFuture<Void> run = CompletableFuture.runAsync(
                () -> control.runStep("Waiting", executions::incrementAndGet));

        await(() -> control.snapshot().nextStepStatus() == TaskStepStatus.WAITING
                && "Waiting".equals(control.snapshot().nextStep()));
        control.requestStop();
        assertThrows(Exception.class, () -> run.get(2, TimeUnit.SECONDS));
        control.stopped();

        assertEquals(0, executions.get());
        assertEquals(TaskExecutionState.STOPPED, control.snapshot().state());
        assertTrue(control.snapshot().history().stream()
                .anyMatch(event -> event.status() == TaskStepStatus.STOPPED));
    }

    @Test
    void executionHistoryDropsOldestEventsAtTheConfiguredLimit() {
        TaskExecutionControl control = control(ControlledExecutionCapability.STEP_AWARE);
        control.resume();

        for (int index = 0; index < TaskExecutionControl.HISTORY_LIMIT; index++) {
            control.skipStep("Step " + index);
        }

        assertEquals(TaskExecutionControl.HISTORY_LIMIT, control.snapshot().history().size());
        assertFalse(control.snapshot().history().stream().anyMatch(event -> event.sequence() == 1));
        assertEquals(TaskStepStatus.SKIPPED, control.snapshot().lastStepStatus());
    }

    private static TaskExecutionControl control(ControlledExecutionCapability capability) {
        AccountDescriptor profile = new AccountDescriptor(7L, "Test", "0", true, 1L, 30L);
        return new TaskExecutionControl(profile,
                new TaskRegistration(TpDailyTaskEnum.BANK, capability), null);
    }

    private static void await(Check condition) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (!condition.evaluate() && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertTrue(condition.evaluate(), "Condition was not met before timeout");
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(2, TimeUnit.SECONDS)) {
                throw new AssertionError("Latch timed out");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw StopExecutionException.userCancelled();
        }
    }

    @FunctionalInterface
    private interface Check {
        boolean evaluate();
    }
}
