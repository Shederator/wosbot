package dev.frostguard.app.panel.scheduler;

import dev.frostguard.api.configs.TpDailyTaskEnum;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.LongProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleLongProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

import java.time.LocalDateTime;
import java.util.Objects;

public final class TaskManagerAux {

    private final TaskIdentity identity;
    private final RunWindow window;
    private final QueueFlags flags;

    public TaskManagerAux(
        String taskName,
        LocalDateTime lastExecution,
        LocalDateTime nextExecution,
        TpDailyTaskEnum taskEnum,
        Long profileId,
        long nearestMinutesUntilExecution,
        boolean hasReadyTask,
        boolean isScheduled,
        boolean executing,
        String customTaskName
    ) {
        identity = new TaskIdentity(taskName, taskEnum, profileId, customTaskName);
        window = new RunWindow(lastExecution, nextExecution, nearestMinutesUntilExecution);
        flags = new QueueFlags(hasReadyTask, isScheduled, executing);
    }

    public String getCustomTaskName() { return identity.customName.get(); }

    public StringProperty customTaskNameProperty() { return identity.customName; }

    public void setCustomTaskName(String value) { identity.customName.set(blank(value)); }

    public String getTaskName() { return identity.label.get(); }

    public StringProperty taskNameProperty() { return identity.label; }

    public void setTaskName(String value) { identity.label.set(blank(value)); }

    public LocalDateTime getLastExecution() { return window.previousRun.get(); }

    public ObjectProperty<LocalDateTime> lastExecutionProperty() { return window.previousRun; }

    public void setLastExecution(LocalDateTime value) { window.previousRun.set(value); }

    public LocalDateTime getNextExecution() { return window.upcomingRun.get(); }

    public ObjectProperty<LocalDateTime> nextExecutionProperty() { return window.upcomingRun; }

    public void setNextExecution(LocalDateTime value) { window.upcomingRun.set(value); }

    public TpDailyTaskEnum getTaskEnum() { return identity.kind.get(); }

    public ObjectProperty<TpDailyTaskEnum> taskEnumProperty() { return identity.kind; }

    public void setTaskEnum(TpDailyTaskEnum value) { identity.kind.set(value); }

    public long getProfileId() { return identity.accountId.get(); }

    public LongProperty profileIdProperty() { return identity.accountId; }

    public void setProfileId(long value) { identity.accountId.set(value); }

    public long getNearestMinutesUntilExecution() { return window.minutesAway.get(); }

    public LongProperty nearestMinutesUntilExecutionProperty() { return window.minutesAway; }

    public void setNearestMinutesUntilExecution(long value) { window.minutesAway.set(value); }

    public boolean hasReadyTask() { return flags.ready.get(); }

    public BooleanProperty hasReadyTaskProperty() { return flags.ready; }

    public void setHasReadyTask(boolean value) { flags.ready.set(value); }

    public boolean isScheduled() { return flags.waiting.get(); }

    public BooleanProperty scheduledProperty() { return flags.waiting; }

    public void setScheduled(boolean value) { flags.waiting.set(value); }

    public boolean isExecuting() { return flags.inFlight.get(); }

    public BooleanProperty executingProperty() { return flags.inFlight; }

    public void setExecuting(boolean value) { flags.inFlight.set(value); }

    public boolean isCustomTask() { return !getCustomTaskName().isBlank(); }

    public boolean hasAnyTimestamp() { return getLastExecution() != null || getNextExecution() != null; }

    public boolean isActionable() { return isExecuting() || isScheduled() || hasReadyTask(); }

    public String displayName() { return isCustomTask() ? getCustomTaskName() : getTaskName(); }

    private static String blank(String value) { return Objects.toString(value, ""); }

    private static final class TaskIdentity {
        private final StringProperty label;
        private final ObjectProperty<TpDailyTaskEnum> kind;
        private final LongProperty accountId;
        private final StringProperty customName;

        private TaskIdentity(String taskName, TpDailyTaskEnum taskEnum, Long profileId, String customTaskName) {
            label = new SimpleStringProperty(blank(taskName));
            kind = new SimpleObjectProperty<>(taskEnum);
            accountId = new SimpleLongProperty(profileId == null ? 0L : profileId);
            customName = new SimpleStringProperty(blank(customTaskName));
        }
    }

    private static final class RunWindow {
        private final ObjectProperty<LocalDateTime> previousRun;
        private final ObjectProperty<LocalDateTime> upcomingRun;
        private final LongProperty minutesAway;

        private RunWindow(LocalDateTime lastExecution, LocalDateTime nextExecution, long nearestMinutesUntilExecution) {
            previousRun = new SimpleObjectProperty<>(lastExecution);
            upcomingRun = new SimpleObjectProperty<>(nextExecution);
            minutesAway = new SimpleLongProperty(nearestMinutesUntilExecution);
        }
    }

    private static final class QueueFlags {
        private final BooleanProperty ready;
        private final BooleanProperty waiting;
        private final BooleanProperty inFlight;

        private QueueFlags(boolean hasReadyTask, boolean scheduled, boolean executing) {
            ready = new SimpleBooleanProperty(hasReadyTask);
            waiting = new SimpleBooleanProperty(scheduled);
            inFlight = new SimpleBooleanProperty(executing);
        }
    }
}
