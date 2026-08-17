package dev.frostguard.api.domain;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Scheduling and execution metadata for a single task instance
 * within the automation queue.
 */
public class TaskStateData {

    /** High-level execution classification. */
    public enum ExecutionPhase { IDLE, PENDING, EXECUTING, COMPLETED }

    private Long ownerProfileRef;
    private int taskCode;
    private String taskLabel;
    private boolean pending;
    private boolean executing;
    private LocalDateTime lastCompletion;
    private LocalDateTime scheduledAt;

    /* ── static factory ── */

    public static TaskStateData of(Long profileId, int code, String label,
                                   boolean queued, boolean running,
                                   LocalDateTime lastRun, LocalDateTime nextRun) {
        TaskStateData d = new TaskStateData();
        d.ownerProfileRef = profileId;
        d.taskCode = code;
        d.taskLabel = label;
        d.pending = queued;
        d.executing = running;
        d.lastCompletion = lastRun;
        d.scheduledAt = nextRun;
        return d;
    }

    /* ── no-arg for frameworks ── */
    public TaskStateData() {}

    /* ── derived ── */

    public ExecutionPhase currentPhase() {
        if (executing) return ExecutionPhase.EXECUTING;
        if (pending)   return ExecutionPhase.PENDING;
        if (lastCompletion != null) return ExecutionPhase.COMPLETED;
        return ExecutionPhase.IDLE;
    }

    public boolean hasCustomName() {
        return taskLabel != null && !taskLabel.isBlank();
    }

    /* ── accessors ── */

    public Long getOwnerProfileRef()            { return ownerProfileRef; }
    public void setOwnerProfileRef(Long id)     { this.ownerProfileRef = id; }

    public int getTaskCode()                    { return taskCode; }
    public void setTaskCode(int c)              { this.taskCode = c; }

    public String getTaskLabel()                { return taskLabel; }
    public void setTaskLabel(String l)          { this.taskLabel = l; }

    public boolean isPending()                  { return pending; }
    public void setPending(boolean p)           { this.pending = p; }

    public boolean isExecuting()                { return executing; }
    public void setExecuting(boolean e)         { this.executing = e; }

    public LocalDateTime getLastCompletion()            { return lastCompletion; }
    public void setLastCompletion(LocalDateTime ts)     { this.lastCompletion = ts; }

    public LocalDateTime getScheduledAt()               { return scheduledAt; }
    public void setScheduledAt(LocalDateTime ts)        { this.scheduledAt = ts; }

    /* ── legacy delegates ── */

    public Long getAccountId()          { return ownerProfileRef; }
    public void setAccountId(Long id)   { this.ownerProfileRef = id; }
    public int getRoutineId()           { return taskCode; }
    public void setRoutineId(int c)     { this.taskCode = c; }
    public String getName()             { return taskLabel; }
    public void setName(String n)       { this.taskLabel = n; }
    public boolean isQueued()           { return pending; }
    public void setQueued(boolean q)    { this.pending = q; }
    public boolean isRunning()          { return executing; }
    public void setRunning(boolean r)   { this.executing = r; }
    public LocalDateTime getPreviousRun()           { return lastCompletion; }
    public void setPreviousRun(LocalDateTime ts)    { this.lastCompletion = ts; }
    public LocalDateTime getUpcomingRun()            { return scheduledAt; }
    public void setUpcomingRun(LocalDateTime ts)     { this.scheduledAt = ts; }
    public void setTaskId(int id)                   { this.taskCode = id; }
    public int getTaskId()                          { return taskCode; }
    public void setScheduled(boolean s)             { this.pending = s; }
    public boolean isScheduled()                    { return pending; }
    public void setNextExecutionTime(LocalDateTime t) { this.scheduledAt = t; }
    public LocalDateTime getNextExecutionTime()     { return scheduledAt; }
    public void setLastExecutionTime(LocalDateTime t) { this.lastCompletion = t; }
    public LocalDateTime getLastExecutionTime()     { return lastCompletion; }
    public String getCustomTaskName()               { return taskLabel; }
    public void setCustomTaskName(String n)          { this.taskLabel = n; }
    public void setProfileId(long id)               { this.ownerProfileRef = id; }
    public Long getProfileId()                      { return ownerProfileRef; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TaskStateData that)) return false;
        return taskCode == that.taskCode
            && Objects.equals(ownerProfileRef, that.ownerProfileRef);
    }

    @Override
    public int hashCode() { return Objects.hash(ownerProfileRef, taskCode); }

    @Override
    public String toString() {
        return "Task{" + taskLabel + " [" + taskCode + "] " + currentPhase() + "}";
    }
}
