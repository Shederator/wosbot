package dev.frostguard.api.domain;

import java.time.LocalDateTime;

public record TaskExecutionEventData(
        long sequence,
        LocalDateTime occurredAt,
        String stepName,
        TaskStepStatus status,
        String detail) {
}
