package dev.frostguard.api.domain;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Snapshot of the automation engine's operational state at a point in time.
 */
public class BotStateData {

    /** High-level engine phase. */
    public enum OperationalPhase { IDLE, ACTIVE, SUSPENDED, RECOVERING }

    private OperationalPhase phase;
    private boolean engineRunning;
    private LocalDateTime lastHeartbeat;
    private boolean schedulerPaused;

    /* ── static factories ── */

    public static BotStateData running(LocalDateTime heartbeat) {
        BotStateData s = new BotStateData();
        s.phase = OperationalPhase.ACTIVE;
        s.engineRunning = true;
        s.lastHeartbeat = heartbeat;
        s.schedulerPaused = false;
        return s;
    }

    public static BotStateData idle() {
        BotStateData s = new BotStateData();
        s.phase = OperationalPhase.IDLE;
        s.engineRunning = false;
        s.schedulerPaused = false;
        return s;
    }

    public static BotStateData paused() {
        BotStateData s = new BotStateData();
        s.phase = OperationalPhase.SUSPENDED;
        s.engineRunning = true;
        s.schedulerPaused = true;
        return s;
    }

    /* ── no-arg constructor for frameworks ── */
    public BotStateData() {}

    /* ── derived ── */

    public boolean isOperational() {
        return engineRunning && !schedulerPaused;
    }

    /* ── accessors ── */

    public OperationalPhase getPhase()          { return phase; }
    public void setPhase(OperationalPhase p)    { this.phase = p; }

    public boolean isRunning()                  { return engineRunning; }
    public void setRunning(boolean r)           { this.engineRunning = r; }
    public boolean getRunning()                 { return engineRunning; }

    public LocalDateTime getLastHeartbeat()             { return lastHeartbeat; }
    public void setLastHeartbeat(LocalDateTime ts)      { this.lastHeartbeat = ts; }

    public boolean isPaused()                   { return schedulerPaused; }
    public Boolean getPaused()                  { return schedulerPaused; }
    public void setPaused(boolean p)            { this.schedulerPaused = p; }
    public void setActionTime(LocalDateTime ts) { this.lastHeartbeat = ts; }
    public LocalDateTime getActionTime()        { return lastHeartbeat; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof BotStateData that)) return false;
        return engineRunning == that.engineRunning
            && schedulerPaused == that.schedulerPaused
            && phase == that.phase
            && Objects.equals(lastHeartbeat, that.lastHeartbeat);
    }

    @Override
    public int hashCode() {
        return Objects.hash(phase, engineRunning, lastHeartbeat, schedulerPaused);
    }

    @Override
    public String toString() {
        return "BotState{" + phase + ", running=" + engineRunning + ", paused=" + schedulerPaused + "}";
    }
}
