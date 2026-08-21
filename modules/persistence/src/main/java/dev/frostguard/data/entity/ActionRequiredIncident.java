package dev.frostguard.data.entity;

import dev.frostguard.api.domain.ActionRequiredIncidentData;
import dev.frostguard.api.domain.ActionRequiredIncidentReport;
import jakarta.persistence.Access;
import jakarta.persistence.AccessType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "action_required_incident", uniqueConstraints = @UniqueConstraint(
        name = "uq_incident_profile_task_signature",
        columnNames = {"profile_id", "task_key", "incident_signature"}))
@Access(AccessType.FIELD)
public class ActionRequiredIncident {

    @Id
    @Column(name = "id", nullable = false, unique = true, length = 36)
    private String id;

    @Column(name = "profile_id", nullable = false)
    private Long profileId;

    @Column(name = "profile_name", nullable = false, length = 240)
    private String profileName;

    @Column(name = "task_key", nullable = false, length = 180)
    private String taskKey;

    @Column(name = "task_name", nullable = false, length = 240)
    private String taskName;

    @Column(name = "incident_signature", nullable = false, length = 240)
    private String signature;

    @Column(name = "title", nullable = false, length = 500)
    private String title;

    @Column(name = "cause", nullable = false, length = 2000)
    private String cause;

    @Column(name = "expected_state", length = 1000)
    private String expectedState;

    @Column(name = "observed_state", length = 1000)
    private String observedState;

    @Column(name = "last_action", length = 1000)
    private String lastAction;

    @Column(name = "retry_fallback", length = 1500)
    private String retryOrFallback;

    @Column(name = "resource_outcome", length = 1000)
    private String resourceOutcome;

    @Column(name = "log_excerpt", length = 6000)
    private String logExcerpt;

    @Column(name = "retry_at")
    private LocalDateTime retryAt;

    @Column(name = "first_seen_at", nullable = false)
    private LocalDateTime firstSeenAt;

    @Column(name = "last_seen_at", nullable = false)
    private LocalDateTime lastSeenAt;

    @Column(name = "occurrence_count", nullable = false)
    private int occurrenceCount;

    @Column(name = "acknowledged_at")
    private LocalDateTime acknowledgedAt;

    @Column(name = "recovered_at")
    private LocalDateTime recoveredAt;

    public ActionRequiredIncident() {
    }

    public static ActionRequiredIncident firstOccurrence(
            ActionRequiredIncidentReport report, String logExcerpt, LocalDateTime seenAt) {
        ActionRequiredIncident incident = new ActionRequiredIncident();
        incident.id = UUID.randomUUID().toString();
        incident.profileId = report.profileId();
        incident.taskKey = report.taskKey();
        incident.signature = report.signature();
        incident.firstSeenAt = seenAt;
        incident.occurrenceCount = 0;
        incident.recordOccurrence(report, logExcerpt, seenAt);
        return incident;
    }

    public void recordOccurrence(
            ActionRequiredIncidentReport report, String recentLogExcerpt, LocalDateTime seenAt) {
        profileName = report.profileName();
        taskName = report.taskName();
        title = report.title();
        cause = report.cause();
        expectedState = report.expectedState();
        observedState = report.observedState();
        lastAction = report.lastAction();
        retryOrFallback = report.retryOrFallback();
        resourceOutcome = report.resourceOutcome();
        logExcerpt = recentLogExcerpt;
        retryAt = report.retryAt();
        lastSeenAt = seenAt;
        occurrenceCount++;
        acknowledgedAt = null;
        recoveredAt = null;
    }

    public void acknowledge(LocalDateTime acknowledgedAt) {
        if (recoveredAt == null) {
            this.acknowledgedAt = acknowledgedAt;
        }
    }

    public void recover(LocalDateTime recoveredAt) {
        if (this.recoveredAt == null) {
            this.recoveredAt = recoveredAt;
        }
    }

    public ActionRequiredIncidentData toData() {
        return new ActionRequiredIncidentData(id, profileId, profileName, taskKey, taskName,
                signature, title, cause, expectedState, observedState, lastAction,
                retryOrFallback, resourceOutcome, logExcerpt, retryAt, firstSeenAt,
                lastSeenAt, occurrenceCount, acknowledgedAt, recoveredAt);
    }
}
