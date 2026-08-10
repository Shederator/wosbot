package dev.frostguard.api.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * Defines a complete user-created automation workflow. Contains an ordered
 * sequence of {@link AutomationStep} entries that execute in series when
 * the custom routine runs. Designed for JSON serialization for save/load/share.
 */
public class AutomationBlueprint {

    private String title;
    private String notes;
    private String initialScreen; // HOME, WORLD, ANY
    private List<AutomationStep> steps;
    private long createdEpochMs;
    private long modifiedEpochMs;

    public AutomationBlueprint() {
        this.steps = new ArrayList<>();
        this.initialScreen = "ANY";
        this.createdEpochMs = System.currentTimeMillis();
        this.modifiedEpochMs = System.currentTimeMillis();
    }

    public AutomationBlueprint(String title) {
        this();
        this.title = title;
    }

    /**
     * Appends a step, auto-assigning a unique sequential ID.
     */
    public AutomationStep appendStep(AutomationStep step) {
        int ceiling = 0;
        for (AutomationStep existing : steps) {
            if (existing.getStepId() > ceiling) ceiling = existing.getStepId();
        }
        step.setStepId(ceiling + 1);
        steps.add(step);
        this.modifiedEpochMs = System.currentTimeMillis();
        return step;
    }

    /**
     * Removes a step by list position. Does not reassign IDs to preserve
     * graph connectivity references.
     */
    public void dropStep(int position) {
        if (position >= 0 && position < steps.size()) {
            steps.remove(position);
            this.modifiedEpochMs = System.currentTimeMillis();
        }
    }

    public int nextStepId() {
        return steps.size() + 1;
    }

    // ---- Accessors ----

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }

    public String getInitialScreen() { return initialScreen; }
    public void setInitialScreen(String screen) { this.initialScreen = screen; }

    public List<AutomationStep> getSteps() { return steps; }
    public void setSteps(List<AutomationStep> steps) { this.steps = steps; }

    public long getCreatedEpochMs() { return createdEpochMs; }
    public void setCreatedEpochMs(long ms) { this.createdEpochMs = ms; }

    public long getModifiedEpochMs() { return modifiedEpochMs; }
    public void setModifiedEpochMs(long ms) { this.modifiedEpochMs = ms; }

    // Legacy compatibility
    public String getName() { return title; }
    public void setName(String n) { this.title = n; }
    public String getDescription() { return notes; }
    public void setDescription(String d) { this.notes = d; }
    public String getStartLocation() { return initialScreen; }
    public void setStartLocation(String l) { this.initialScreen = l; }
    public List<AutomationStep> getNodes() { return steps; }
    public void setNodes(List<AutomationStep> nodes) { this.steps = nodes; }
    public long getCreatedAt() { return createdEpochMs; }
    public void setCreatedAt(long t) { this.createdEpochMs = t; }
    public long getUpdatedAt() { return modifiedEpochMs; }
    public void setUpdatedAt(long t) { this.modifiedEpochMs = t; }
    public AutomationStep addNode(AutomationStep s) { return appendStep(s); }
    public void removeNode(int i) { dropStep(i); }
    public int getNextNodeId() { return nextStepId(); }

    @Override
    public String toString() {
        return String.format("Blueprint[%s] (%d steps)", title, steps.size());
    }
}
