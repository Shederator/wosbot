package dev.frostguard.engine.service;

import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.TaskExecutionSnapshotData;
import dev.frostguard.api.domain.TaskExecutionState;
import dev.frostguard.engine.emulator.EmulatorController;
import dev.frostguard.engine.error.StopExecutionException;
import dev.frostguard.engine.listener.TaskExecutionListener;
import dev.frostguard.engine.schedule.DelayedTask;
import dev.frostguard.engine.schedule.DelayedTaskRegistry;
import dev.frostguard.engine.schedule.ExecutionContext;
import dev.frostguard.engine.schedule.TaskExecutionControl;
import dev.frostguard.engine.schedule.TaskRegistration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class ControlledTaskExecutionService {

    private static final Logger LOG = LoggerFactory.getLogger(ControlledTaskExecutionService.class);
    private static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(10);
    private static final ControlledTaskExecutionService INSTANCE = new ControlledTaskExecutionService();

    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "TaskWorkbench");
        thread.setDaemon(true);
        return thread;
    });
    private final CopyOnWriteArrayList<TaskExecutionListener> listeners = new CopyOnWriteArrayList<>();

    private volatile TaskExecutionControl currentControl;
    private volatile ExecutionContext currentContext;

    private ControlledTaskExecutionService() {
    }

    public static ControlledTaskExecutionService obtain() {
        return INSTANCE;
    }

    public List<TaskRegistration> getAvailableTasks() {
        return DelayedTaskRegistry.getWorkbenchTasks();
    }

    public synchronized TaskExecutionSnapshotData start(
            AccountDescriptor profile, TaskRegistration registration) {
        if (profile == null || profile.getId() == null) {
            throw new IllegalArgumentException("A persisted profile is required");
        }
        if (registration == null) {
            throw new IllegalArgumentException("A registered task is required");
        }
        if (!getAvailableTasks().contains(registration)) {
            throw new IllegalArgumentException("Task is not available for workbench execution");
        }
        if (ScheduleService.obtain().isEngineRunning()) {
            throw new IllegalStateException("Stop the scheduler before starting a workbench run");
        }
        TaskExecutionControl existing = currentControl;
        if (existing != null && existing.snapshot().state().isActive()) {
            throw new IllegalStateException("Another workbench run is already active");
        }

        TaskExecutionControl control = new TaskExecutionControl(profile, registration, this::publish);
        currentControl = control;
        control.publishSnapshot();
        LOG.info("Starting controlled task run: runId={}, profile={} ({}), task={}, capability={}",
                control.snapshot().runId(), profile.getName(), profile.getId(),
                registration.taskType(), registration.controlledExecutionCapability());
        executor.submit(() -> execute(profile, registration, control));
        return control.snapshot();
    }

    public void pause() {
        activeControl().pause();
    }

    public void executeNextStep() {
        activeControl().executeNextStep();
    }

    public void resume() {
        activeControl().resume();
    }

    public void stop() {
        TaskExecutionControl control = currentControl;
        if (control == null) {
            return;
        }
        control.requestStop();
        ExecutionContext context = currentContext;
        if (context != null) {
            context.cancel();
        }
    }

    public TaskExecutionSnapshotData getSnapshot() {
        TaskExecutionControl control = currentControl;
        return control == null ? TaskExecutionSnapshotData.idle() : control.snapshot();
    }

    public boolean hasActiveRun() {
        return getSnapshot().state().isActive();
    }

    public void addListener(TaskExecutionListener listener) {
        if (listener != null) {
            listeners.addIfAbsent(listener);
        }
    }

    public void removeListener(TaskExecutionListener listener) {
        listeners.remove(listener);
    }

    public void shutdown() {
        stop();
        executor.shutdown();
        try {
            if (!executor.awaitTermination(SHUTDOWN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                executor.shutdownNow();
                throw new IllegalStateException("Workbench task did not stop within "
                        + SHUTDOWN_TIMEOUT.toSeconds() + " seconds");
            }
        } catch (InterruptedException exception) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while stopping workbench execution", exception);
        }
    }

    private void execute(AccountDescriptor profile, TaskRegistration registration,
            TaskExecutionControl control) {
        ExecutionContext context = null;
        DelayedTask task = null;
        try {
            control.awaitExecutionStart();
            EmulatorController.getInstance().initialize();
            task = DelayedTaskRegistry.create(registration.taskType(), profile);
            task.setRecurring(false);
            task.attachExecutionControl(control);
            context = new ExecutionContext(task);
            currentContext = context;
            if (control.isStopRequested()) {
                context.cancel();
            }
            task.run();
            if (control.isStopRequested()) {
                control.stopped();
                LOG.info("Controlled task run stopped: runId={}, profile={} ({}), task={}",
                        control.snapshot().runId(), profile.getName(), profile.getId(), registration.taskType());
            } else {
                control.complete();
                LOG.info("Controlled task run completed: runId={}, profile={} ({}), task={}",
                        control.snapshot().runId(), profile.getName(), profile.getId(), registration.taskType());
            }
        } catch (StopExecutionException exception) {
            if (control.isStopRequested() || exception.isCancellation()) {
                control.stopped();
                LOG.info("Controlled task run stopped: runId={}, profile={} ({}), task={}",
                        control.snapshot().runId(), profile.getName(), profile.getId(), registration.taskType());
            } else {
                control.fail(exception);
                LOG.error("Controlled task run failed: runId={}, profile={} ({}), task={}",
                        control.snapshot().runId(), profile.getName(), profile.getId(), registration.taskType(), exception);
            }
        } catch (RuntimeException exception) {
            control.fail(exception);
            LOG.error("Controlled task run failed: runId={}, profile={} ({}), task={}",
                    control.snapshot().runId(), profile.getName(), profile.getId(), registration.taskType(), exception);
        } finally {
            if (task != null) {
                task.detachExecutionControl(control);
            }
            if (context != null) {
                context.clear();
            }
            currentContext = null;
        }
    }

    private TaskExecutionControl activeControl() {
        TaskExecutionControl control = currentControl;
        if (control == null || !control.snapshot().state().isActive()) {
            throw new IllegalStateException("No workbench run is active");
        }
        return control;
    }

    private void publish(TaskExecutionSnapshotData snapshot) {
        listeners.forEach(listener -> listener.onTaskExecutionChanged(snapshot));
    }
}
