package dev.frostguard.engine.service;

import dev.frostguard.api.domain.ActionRequiredIncidentData;
import dev.frostguard.api.domain.ActionRequiredIncidentReport;
import dev.frostguard.api.domain.TaskFailureReport;
import dev.frostguard.api.domain.TaskFailureStreakData;
import dev.frostguard.data.repository.TaskFailureStreakRepository;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Persistent failure-budget gateway for both queue-thrown exceptions and tasks
 * that catch and reschedule their own failures. A task must explicitly call
 * {@link #recordFailure(TaskFailureReport)} when it converts a failure into a
 * normal return; failures that are completely swallowed cannot be inferred.
 */
public class TaskFailureIncidentService {

    public static final int DEFAULT_ESCALATION_THRESHOLD = 3;
    public static final Duration DEFAULT_UNHANDLED_RETRY_DELAY = Duration.ofMinutes(5);

    private static volatile TaskFailureIncidentService instance;

    private final TaskFailureStreakRepository streakRepository;
    private final ActionRequiredIncidentService incidentService;

    TaskFailureIncidentService(
            TaskFailureStreakRepository streakRepository,
            ActionRequiredIncidentService incidentService) {
        this.streakRepository = Objects.requireNonNull(streakRepository);
        this.incidentService = Objects.requireNonNull(incidentService);
    }

    public static TaskFailureIncidentService obtain() {
        TaskFailureIncidentService current = instance;
        if (current != null) {
            return current;
        }
        synchronized (TaskFailureIncidentService.class) {
            if (instance == null) {
                instance = new TaskFailureIncidentService(
                        TaskFailureStreakRepository.getRepository(),
                        ActionRequiredIncidentService.obtain());
            }
            return instance;
        }
    }

    /**
     * Records an internally handled attempt. Callers retain ownership of task
     * rescheduling; this method owns only streak persistence and escalation.
     */
    public synchronized FailureDecision recordFailure(TaskFailureReport report) {
        TaskFailureStreakData streak = streakRepository.recordFailure(
                report.profileId(), report.taskKey(), report.signature(), LocalDateTime.now());
        if (streak.consecutiveFailures() < report.escalationThreshold()) {
            return new FailureDecision(
                    streak.consecutiveFailures(), false, report.retryAt(), Optional.empty());
        }

        ActionRequiredIncidentData incident = incidentService.report(new ActionRequiredIncidentReport(
                report.profileId(),
                report.profileName(),
                report.taskKey(),
                report.taskName(),
                "task-failure." + report.signature(),
                report.title(),
                report.cause(),
                report.expectedState(),
                report.observedState(),
                report.lastAction() + " Consecutive failed attempts: "
                        + streak.consecutiveFailures() + ".",
                report.retryOrFallback(),
                report.resourceOutcome(),
                report.retryAt()));
        return new FailureDecision(
                streak.consecutiveFailures(), true, report.retryAt(), Optional.of(incident));
    }

    public FailureDecision recordUnhandledFailure(
            long profileId, String profileName, String taskKey, String taskName,
            Throwable failure, LocalDateTime failedAt) {
        Throwable root = rootCause(failure);
        String type = root.getClass().getSimpleName();
        String message = readableMessage(root);
        LocalDateTime retryAt = failedAt.plus(DEFAULT_UNHANDLED_RETRY_DELAY);
        return recordFailure(new TaskFailureReport(
                profileId,
                profileName,
                taskKey,
                taskName,
                stableExceptionSignature(root),
                "Task repeatedly failed",
                taskName + " repeatedly failed with " + type + ": " + message,
                "Task completes without an unhandled exception",
                type + ": " + message,
                "The queue captured an unhandled task exception",
                "Retry no earlier than " + retryAt + "; keep other queue work available",
                "No profile-wide game or emulator cleanup was requested",
                retryAt,
                DEFAULT_ESCALATION_THRESHOLD));
    }

    public synchronized int recordSuccess(long profileId, String taskKey) {
        streakRepository.clear(profileId, taskKey);
        return incidentService.recoverTask(profileId, taskKey);
    }

    static String stableExceptionSignature(Throwable failure) {
        String normalizedMessage = readableMessage(failure)
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\d+", "#")
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
        if (normalizedMessage.length() > 140) {
            normalizedMessage = normalizedMessage.substring(0, 140);
        }
        if (normalizedMessage.isBlank()) {
            normalizedMessage = "no-message";
        }
        String signature = failure.getClass().getName().toLowerCase(Locale.ROOT)
                + "." + normalizedMessage;
        return signature.length() > 220 ? signature.substring(0, 220) : signature;
    }

    private static Throwable rootCause(Throwable failure) {
        Throwable current = Objects.requireNonNull(failure);
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    private static String readableMessage(Throwable failure) {
        String message = Objects.requireNonNullElse(failure.getMessage(), "No message provided").trim();
        return message.isBlank() ? "No message provided" : message;
    }

    public record FailureDecision(
            int consecutiveFailures,
            boolean escalated,
            LocalDateTime retryAt,
            Optional<ActionRequiredIncidentData> incident) {
    }
}
