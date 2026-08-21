package dev.frostguard.data.entity;

import dev.frostguard.api.domain.TaskFailureStreakData;
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
@Table(name = "task_failure_streak", uniqueConstraints = @UniqueConstraint(
        name = "uq_task_failure_profile_task",
        columnNames = {"profile_id", "task_key"}))
@Access(AccessType.FIELD)
public class TaskFailureStreak {

    @Id
    @Column(name = "id", nullable = false, unique = true, length = 36)
    private String id;

    @Column(name = "profile_id", nullable = false)
    private Long profileId;

    @Column(name = "task_key", nullable = false, length = 180)
    private String taskKey;

    @Column(name = "failure_signature", nullable = false, length = 240)
    private String signature;

    @Column(name = "consecutive_failures", nullable = false)
    private int consecutiveFailures;

    @Column(name = "first_failure_at", nullable = false)
    private LocalDateTime firstFailureAt;

    @Column(name = "last_failure_at", nullable = false)
    private LocalDateTime lastFailureAt;

    public TaskFailureStreak() {
    }

    public static TaskFailureStreak firstFailure(
            long profileId, String taskKey, String signature, LocalDateTime failedAt) {
        TaskFailureStreak streak = new TaskFailureStreak();
        streak.id = UUID.randomUUID().toString();
        streak.profileId = profileId;
        streak.taskKey = taskKey;
        streak.start(signature, failedAt);
        return streak;
    }

    public void record(String failureSignature, LocalDateTime failedAt) {
        if (!signature.equals(failureSignature)) {
            start(failureSignature, failedAt);
            return;
        }
        consecutiveFailures++;
        lastFailureAt = failedAt;
    }

    private void start(String failureSignature, LocalDateTime failedAt) {
        signature = failureSignature;
        consecutiveFailures = 1;
        firstFailureAt = failedAt;
        lastFailureAt = failedAt;
    }

    public TaskFailureStreakData toData() {
        return new TaskFailureStreakData(profileId, taskKey, signature,
                consecutiveFailures, firstFailureAt, lastFailureAt);
    }
}
