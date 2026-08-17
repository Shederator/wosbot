package dev.frostguard.api.domain;

import java.io.Serializable;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Execution metrics for a single automated routine. Tracks run counts,
 * cumulative time, and failure rates for diagnostics/analytics.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class JobMetrics implements Serializable {

    private String routineName;
    private int executionCount;
    private long cumulativeTimeMs;
    private String lastExecutedAt;
    private int ocrErrorCount;
    private int matchErrorCount;

    public JobMetrics() {}

    public JobMetrics(String routineName) {
        this.routineName = routineName;
        this.executionCount = 0;
        this.cumulativeTimeMs = 0;
        this.ocrErrorCount = 0;
        this.matchErrorCount = 0;
    }

    public String getRoutineName() { return routineName; }
    public void setRoutineName(String name) { this.routineName = name; }

    public int getExecutionCount() { return executionCount; }
    public void setExecutionCount(int count) { this.executionCount = count; }

    public long getCumulativeTimeMs() { return cumulativeTimeMs; }
    public void setCumulativeTimeMs(long ms) { this.cumulativeTimeMs = ms; }

    public String getLastExecutedAt() { return lastExecutedAt; }
    public void setLastExecutedAt(String ts) { this.lastExecutedAt = ts; }

    public long computeAverageTimeMs() {
        return executionCount > 0 ? cumulativeTimeMs / executionCount : 0;
    }

    public double computeOcrErrorRate() {
        return executionCount > 0 ? (double) ocrErrorCount / executionCount : 0.0;
    }

    public double computeMatchErrorRate() {
        return executionCount > 0 ? (double) matchErrorCount / executionCount : 0.0;
    }

    public int getOcrErrorCount() { return ocrErrorCount; }
    public void setOcrErrorCount(int count) { this.ocrErrorCount = count; }

    public int getMatchErrorCount() { return matchErrorCount; }
    public void setMatchErrorCount(int count) { this.matchErrorCount = count; }

    // Legacy compatibility
    public String getTaskName() { return routineName; }
    public void setTaskName(String n) { this.routineName = n; }
    public int getNumberOfRuns() { return executionCount; }
    public void setNumberOfRuns(int n) { this.executionCount = n; }
    public long getTotalExecutionTimeMs() { return cumulativeTimeMs; }
    public void setTotalExecutionTimeMs(long t) { this.cumulativeTimeMs = t; }
    public String getLastRunTime() { return lastExecutedAt; }
    public void setLastRunTime(String t) { this.lastExecutedAt = t; }
    public long getAverageExecutionTimeMs() { return computeAverageTimeMs(); }
    public double getAverageOcrFailures() { return computeOcrErrorRate(); }
    public double getAverageTemplateFailures() { return computeMatchErrorRate(); }
    public int getTotalOcrFailures() { return ocrErrorCount; }
    public void setTotalOcrFailures(int f) { this.ocrErrorCount = f; }
    public int getTotalTemplateSearchFailures() { return matchErrorCount; }
    public void setTotalTemplateSearchFailures(int f) { this.matchErrorCount = f; }
}
