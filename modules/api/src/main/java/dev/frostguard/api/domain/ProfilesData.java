package dev.frostguard.api.domain;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Aggregated performance metrics for a single managed profile.
 * Holds per-routine execution stats and arbitrary named counters.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ProfilesData implements Serializable {

    private Map<String, JobMetrics> routineStats = new HashMap<>();
    private Map<String, Integer> namedCounters = new HashMap<>();

    public ProfilesData() {}

    public Map<String, JobMetrics> getRoutineStats() { return routineStats; }
    public void setRoutineStats(Map<String, JobMetrics> routineStats) { this.routineStats = routineStats; }

    public Map<String, Integer> getNamedCounters() { return namedCounters; }
    public void setNamedCounters(Map<String, Integer> namedCounters) { this.namedCounters = namedCounters; }

    // Legacy compatibility
    public Map<String, JobMetrics> getTaskStatistics() { return routineStats; }
    public void setTaskStatistics(Map<String, JobMetrics> ts) { this.routineStats = ts; }
    public Map<String, Integer> getCustomCounters() { return namedCounters; }
    public void setCustomCounters(Map<String, Integer> cc) { this.namedCounters = cc; }
}
