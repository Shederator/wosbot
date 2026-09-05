package dev.frostguard.engine.schedule;

import dev.frostguard.api.configs.ControlledExecutionCapability;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.TaskExecutionEventData;
import dev.frostguard.api.domain.TaskExecutionSnapshotData;
import dev.frostguard.api.domain.TaskExecutionState;
import dev.frostguard.api.domain.TaskFlowNodeData;
import dev.frostguard.api.domain.TaskStepStatus;
import dev.frostguard.engine.error.StopExecutionException;

import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.UUID;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class TaskExecutionControl {

    static final int HISTORY_LIMIT = 1_000;

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition permissionChanged = lock.newCondition();
    private final String runId = UUID.randomUUID().toString();
    private final Long profileId;
    private final String profileName;
    private final TpDailyTaskEnum taskType;
    private final ControlledExecutionCapability capability;
    private final Consumer<TaskExecutionSnapshotData> publisher;
    private final Deque<TaskExecutionEventData> history = new ArrayDeque<>();

    private TaskExecutionState state = TaskExecutionState.PAUSED;
    private String currentStepId;
    private String currentStep;
    private TaskStepStatus currentStepStatus;
    private String nextStepId;
    private String nextStep;
    private TaskStepStatus nextStepStatus;
    private String lastStepId;
    private String lastStep;
    private TaskStepStatus lastStepStatus;
    private String message;
    private long sequence;
    private int stepPermits;
    private boolean continuous;
    private boolean stopRequested;

    public TaskExecutionControl(AccountDescriptor profile, TaskRegistration registration,
            Consumer<TaskExecutionSnapshotData> publisher) {
        this.profileId = profile.getId();
        this.profileName = profile.getName();
        this.taskType = registration.taskType();
        this.capability = registration.controlledExecutionCapability();
        this.publisher = publisher;
        TaskFlowNodeData entryStep = registration.flowDefinition().entryStep();
        this.nextStepId = entryStep.id();
        this.nextStep = entryStep.label();
        this.nextStepStatus = TaskStepStatus.WAITING;
    }

    public ControlledExecutionCapability capability() {
        return capability;
    }

    public void awaitExecutionStart() {
        lock.lock();
        try {
            while (!stopRequested && !continuous && stepPermits == 0) {
                try {
                    permissionChanged.await();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    stopRequested = true;
                }
            }
            if (stopRequested) {
                throw StopExecutionException.userCancelled();
            }
            state = TaskExecutionState.RUNNING;
            publishLocked();
        } finally {
            lock.unlock();
        }
    }

    public void runStep(String stepName, Runnable action) {
        runStep(stepName, stepName, () -> {
            action.run();
            return null;
        });
    }

    public <T> T runStep(String stepName, Supplier<T> action) {
        return runStep(stepName, stepName, action);
    }

    public void runStep(String stepId, String stepName, Runnable action) {
        runStep(stepId, stepName, () -> {
            action.run();
            return null;
        });
    }

    public <T> T runStep(String stepId, String stepName, Supplier<T> action) {
        awaitPermission(stepId, stepName);
        try {
            T result = action.get();
            finishStep(TaskStepStatus.COMPLETED, null);
            return result;
        } catch (RuntimeException exception) {
            if (!isStopRequested()) {
                finishStep(TaskStepStatus.FAILED, failureMessage(exception));
            }
            throw exception;
        }
    }

    public void skipStep(String stepName) {
        skipStep(stepName, stepName);
    }

    public void skipStep(String stepId, String stepName) {
        awaitPermission(stepId, stepName);
        finishStep(TaskStepStatus.SKIPPED, null);
    }

    public void pause() {
        lock.lock();
        try {
            if (!state.isActive() || state == TaskExecutionState.STOPPING) {
                return;
            }
            continuous = false;
            stepPermits = 0;
            state = currentStepStatus == TaskStepStatus.STARTED
                    ? TaskExecutionState.PAUSE_REQUESTED
                    : TaskExecutionState.PAUSED;
            publishLocked();
        } finally {
            lock.unlock();
        }
    }

    public void executeNextStep() {
        lock.lock();
        try {
            if (state != TaskExecutionState.PAUSED || stopRequested) {
                return;
            }
            continuous = false;
            stepPermits = 1;
            permissionChanged.signalAll();
            publishLocked();
        } finally {
            lock.unlock();
        }
    }

    public void resume() {
        lock.lock();
        try {
            if (!state.isActive() || state == TaskExecutionState.STOPPING) {
                return;
            }
            continuous = true;
            stepPermits = 0;
            state = TaskExecutionState.RUNNING;
            permissionChanged.signalAll();
            publishLocked();
        } finally {
            lock.unlock();
        }
    }

    public void requestStop() {
        lock.lock();
        try {
            if (!state.isActive()) {
                return;
            }
            stopRequested = true;
            continuous = false;
            stepPermits = 0;
            state = TaskExecutionState.STOPPING;
            permissionChanged.signalAll();
            publishLocked();
        } finally {
            lock.unlock();
        }
    }

    public boolean isStopRequested() {
        lock.lock();
        try {
            return stopRequested;
        } finally {
            lock.unlock();
        }
    }

    public void complete() {
        transitionToTerminal(TaskExecutionState.COMPLETED, null);
    }

    public void fail(Throwable failure) {
        transitionToTerminal(TaskExecutionState.FAILED, failureMessage(failure));
    }

    public void stopped() {
        lock.lock();
        try {
            if (currentStep != null && currentStepStatus != TaskStepStatus.STOPPED) {
                addEvent(currentStepId, currentStep, TaskStepStatus.STOPPED, null);
                lastStepId = currentStepId;
                lastStep = currentStep;
                lastStepStatus = TaskStepStatus.STOPPED;
            } else if (nextStep != null) {
                addEvent(nextStepId, nextStep, TaskStepStatus.STOPPED, null);
                lastStepId = nextStepId;
                lastStep = nextStep;
                lastStepStatus = TaskStepStatus.STOPPED;
            }
            currentStepId = null;
            currentStep = null;
            currentStepStatus = null;
            nextStepId = null;
            nextStep = null;
            nextStepStatus = null;
            state = TaskExecutionState.STOPPED;
            message = "Execution stopped";
            publishLocked();
        } finally {
            lock.unlock();
        }
    }

    public TaskExecutionSnapshotData snapshot() {
        lock.lock();
        try {
            return snapshotLocked();
        } finally {
            lock.unlock();
        }
    }

    public void publishSnapshot() {
        lock.lock();
        try {
            publishLocked();
        } finally {
            lock.unlock();
        }
    }

    private void awaitPermission(String stepId, String stepName) {
        if (stepId == null || stepId.isBlank()) {
            throw new IllegalArgumentException("Step id is required");
        }
        if (stepName == null || stepName.isBlank()) {
            throw new IllegalArgumentException("Step name is required");
        }
        lock.lock();
        try {
            nextStepId = stepId;
            nextStep = stepName;
            nextStepStatus = TaskStepStatus.WAITING;
            state = TaskExecutionState.PAUSED;
            addEvent(stepId, stepName, TaskStepStatus.WAITING, null);
            publishLocked();

            while (!stopRequested && !continuous && stepPermits == 0) {
                try {
                    permissionChanged.await();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    stopRequested = true;
                }
            }
            if (stopRequested) {
                throw StopExecutionException.userCancelled();
            }
            if (!continuous) {
                stepPermits--;
            }
            currentStepId = nextStepId;
            currentStep = nextStep;
            currentStepStatus = TaskStepStatus.STARTED;
            nextStepId = null;
            nextStep = null;
            nextStepStatus = null;
            state = TaskExecutionState.RUNNING;
            addEvent(currentStepId, currentStep, TaskStepStatus.STARTED, null);
            publishLocked();
        } finally {
            lock.unlock();
        }
    }

    private void finishStep(TaskStepStatus status, String detail) {
        lock.lock();
        try {
            String finishedStep = currentStep;
            String finishedStepId = currentStepId;
            addEvent(finishedStepId, finishedStep, status, detail);
            lastStepId = finishedStepId;
            lastStep = finishedStep;
            lastStepStatus = status;
            currentStepId = null;
            currentStep = null;
            currentStepStatus = null;
            message = detail;
            state = stopRequested
                    ? TaskExecutionState.STOPPING
                    : continuous ? TaskExecutionState.RUNNING : TaskExecutionState.PAUSED;
            publishLocked();
        } finally {
            lock.unlock();
        }
    }

    private void transitionToTerminal(TaskExecutionState terminalState, String detail) {
        lock.lock();
        try {
            currentStepId = null;
            currentStep = null;
            currentStepStatus = null;
            nextStepId = null;
            nextStep = null;
            nextStepStatus = null;
            state = terminalState;
            message = detail;
            publishLocked();
        } finally {
            lock.unlock();
        }
    }

    private void addEvent(String stepId, String stepName, TaskStepStatus status, String detail) {
        history.addLast(new TaskExecutionEventData(
                ++sequence, LocalDateTime.now(), stepId, stepName, status, detail));
        while (history.size() > HISTORY_LIMIT) {
            history.removeFirst();
        }
    }

    private void publishLocked() {
        if (publisher != null) {
            publisher.accept(snapshotLocked());
        }
    }

    private TaskExecutionSnapshotData snapshotLocked() {
        return new TaskExecutionSnapshotData(
                runId, profileId, profileName, taskType, state,
                currentStepId, currentStep, currentStepStatus,
                nextStepId, nextStep, nextStepStatus,
                lastStepId, lastStep, lastStepStatus,
                message, new ArrayList<>(history));
    }

    private static String failureMessage(Throwable failure) {
        if (failure == null) {
            return null;
        }
        return failure.getMessage() == null || failure.getMessage().isBlank()
                ? failure.getClass().getSimpleName()
                : failure.getMessage();
    }
}
