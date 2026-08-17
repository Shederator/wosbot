package dev.frostguard.api.domain;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Tracks the execution status and scheduling metadata of a single
 * automated routine bound to a specific profile.
 */
public class DailyTaskStatusData {

    private Long boundProfileId;
    private int taskTypeCode;
    private String customLabel;
    private boolean activated;
    private LocalDateTime scheduledAt;
    private LocalDateTime lastCompletion;
    private Integer staminaMinimumRequired;
    private Integer staminaRegenerationTarget;
    private LocalDateTime staminaEarliestRunnableAt;

    /* ── primary construction via factory ── */

    public static DailyTaskStatusData create(Long profileId, int typeCode, String label,
                                             boolean active, LocalDateTime nextRun, LocalDateTime lastRun) {
        Objects.requireNonNull(profileId, "profileId must not be null");
        DailyTaskStatusData d = new DailyTaskStatusData();
        d.boundProfileId = profileId;
        d.taskTypeCode = typeCode;
        d.customLabel = label;
        d.activated = active;
        d.scheduledAt = nextRun;
        d.lastCompletion = lastRun;
        return d;
    }

    /** Immutable-style copy with updated schedule. */
    public DailyTaskStatusData withUpdatedSchedule(LocalDateTime nextRun) {
        DailyTaskStatusData copy = create(boundProfileId, taskTypeCode, customLabel, activated, nextRun, lastCompletion);
        copy.staminaMinimumRequired = staminaMinimumRequired;
        copy.staminaRegenerationTarget = staminaRegenerationTarget;
        copy.staminaEarliestRunnableAt = staminaEarliestRunnableAt;
        return copy;
    }

    /* ── no-arg for frameworks ── */
    public DailyTaskStatusData() {}

    /** Constructor used by Hibernate HQL projections. */
    public DailyTaskStatusData(Long profileId, int typeCode, LocalDateTime lastRun,
                               LocalDateTime nextRun, String label, Integer minimumRequired,
                               Integer regenerationTarget, LocalDateTime earliestRunnableAt) {
        this.boundProfileId = profileId;
        this.taskTypeCode = typeCode;
        this.lastCompletion = lastRun;
        this.scheduledAt = nextRun;
        this.customLabel = label;
        this.staminaMinimumRequired = minimumRequired;
        this.staminaRegenerationTarget = regenerationTarget;
        this.staminaEarliestRunnableAt = earliestRunnableAt;
        this.activated = true;
    }

    /* ── derived ── */

    public boolean hasCustomName() {
        return customLabel != null && !customLabel.isBlank();
    }

    /* ── accessors ── */

    public Long getBoundProfileId()                     { return boundProfileId; }
    public void setBoundProfileId(Long id)              { this.boundProfileId = id; }

    public int getTaskTypeCode()                        { return taskTypeCode; }
    public void setTaskTypeCode(int code)               { this.taskTypeCode = code; }

    public String getCustomLabel()                      { return customLabel; }
    public void setCustomLabel(String lbl)              { this.customLabel = lbl; }

    public boolean isActivated()                        { return activated; }
    public void setActivated(boolean a)                 { this.activated = a; }

    public LocalDateTime getScheduledAt()               { return scheduledAt; }
    public void setScheduledAt(LocalDateTime ts)        { this.scheduledAt = ts; }

    public LocalDateTime getLastCompletion()            { return lastCompletion; }
    public void setLastCompletion(LocalDateTime ts)     { this.lastCompletion = ts; }

    public Integer getStaminaMinimumRequired()          { return staminaMinimumRequired; }
    public void setStaminaMinimumRequired(Integer value) { this.staminaMinimumRequired = value; }

    public Integer getStaminaRegenerationTarget()       { return staminaRegenerationTarget; }
    public void setStaminaRegenerationTarget(Integer value) { this.staminaRegenerationTarget = value; }

    public LocalDateTime getStaminaEarliestRunnableAt() { return staminaEarliestRunnableAt; }
    public void setStaminaEarliestRunnableAt(LocalDateTime value) { this.staminaEarliestRunnableAt = value; }

    public boolean hasStaminaDeferral() {
        return staminaMinimumRequired != null
            && staminaRegenerationTarget != null
            && staminaEarliestRunnableAt != null;
    }

    /* ── legacy delegates ── */

    public Long getAccountId()          { return boundProfileId; }
    public void setAccountId(Long id)   { this.boundProfileId = id; }
    public int getRoutineTypeId()       { return taskTypeCode; }
    public void setRoutineTypeId(int c) { this.taskTypeCode = c; }
    public int getIdTpDailyTask()       { return taskTypeCode; }
    public void setIdTpDailyTask(int c) { this.taskTypeCode = c; }
    public String getName()             { return customLabel; }
    public void setName(String n)       { this.customLabel = n; }
    public boolean isEnabled()          { return activated; }
    public void setEnabled(boolean e)   { this.activated = e; }
    public LocalDateTime getNextRun()          { return scheduledAt; }
    public void setNextRun(LocalDateTime ts)   { this.scheduledAt = ts; }
    public LocalDateTime getLastRun()          { return lastCompletion; }
    public void setLastRun(LocalDateTime ts)   { this.lastCompletion = ts; }
    public LocalDateTime getNextSchedule()     { return scheduledAt; }
    public void setNextSchedule(LocalDateTime ts) { this.scheduledAt = ts; }
    public LocalDateTime getLastExecution()    { return lastCompletion; }
    public void setLastExecution(LocalDateTime ts) { this.lastCompletion = ts; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DailyTaskStatusData that)) return false;
        return taskTypeCode == that.taskTypeCode
            && Objects.equals(boundProfileId, that.boundProfileId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(boundProfileId, taskTypeCode);
    }

    @Override
    public String toString() {
        return "DailyTask{profile=" + boundProfileId + ", type=" + taskTypeCode
             + ", label=" + customLabel + ", active=" + activated + "}";
    }
}
