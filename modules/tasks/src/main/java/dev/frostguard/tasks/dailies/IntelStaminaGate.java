package dev.frostguard.tasks.dailies;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.OptionalInt;

import dev.frostguard.engine.schedule.StaminaWaitScheduler;
import dev.frostguard.engine.service.StaminaService;

final class IntelStaminaGate {

    private static final Duration UNKNOWN_RETRY_DELAY = Duration.ofMinutes(1);

    private IntelStaminaGate() {
    }

    static Outcome evaluate(StaminaService staminaService, Long profileId, int minimumRequired,
                            LocalDateTime now, StaminaWaitScheduler scheduler) {
        OptionalInt stamina = staminaService.findCurrentStamina(profileId);
        if (stamina.isEmpty()) {
            LocalDateTime retryAt = now.plus(UNKNOWN_RETRY_DELAY);
            scheduler.reschedule(retryAt);
            return new Outcome(Status.UNKNOWN, stamina, retryAt);
        }

        int measured = stamina.getAsInt();
        if (measured < minimumRequired) {
            LocalDateTime retryAt = now.plusMinutes(
                    StaminaService.minutesToRegenerate(measured, minimumRequired));
            scheduler.deferForStamina(minimumRequired, minimumRequired, retryAt, now);
            return new Outcome(Status.INSUFFICIENT, stamina, retryAt);
        }

        return new Outcome(Status.READY, stamina, null);
    }

    enum Status {
        READY,
        UNKNOWN,
        INSUFFICIENT
    }

    record Outcome(Status status, OptionalInt stamina, LocalDateTime retryAt) {
        boolean allowed() {
            return status == Status.READY;
        }
    }
}
