package dev.frostguard.tasks.dailies;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.engine.schedule.StaminaWaitScheduler;
import dev.frostguard.engine.service.StaminaService;

class IntelStaminaGateTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 10, 10, 0);

    @Test
    void unknownStaminaRetriesSoonWithoutCreatingARegenerationDeferral() {
        RecordingScheduler scheduler = new RecordingScheduler();

        IntelStaminaGate.Outcome outcome = IntelStaminaGate.evaluate(
                StaminaService.getServices(), 9201L, 30, NOW, scheduler);

        assertEquals(IntelStaminaGate.Status.UNKNOWN, outcome.status());
        assertFalse(outcome.allowed());
        assertEquals(NOW.plusMinutes(1), outcome.retryAt());
        assertEquals(outcome.retryAt(), scheduler.rescheduledAt);
        assertFalse(scheduler.staminaDeferred);
    }

    @Test
    void measuredZeroUsesTheRealRegenerationDelay() {
        StaminaService service = StaminaService.getServices();
        service.setStamina(9202L, 0);
        RecordingScheduler scheduler = new RecordingScheduler();

        IntelStaminaGate.Outcome outcome = IntelStaminaGate.evaluate(service, 9202L, 30, NOW, scheduler);

        assertEquals(IntelStaminaGate.Status.INSUFFICIENT, outcome.status());
        assertFalse(outcome.allowed());
        assertEquals(0, outcome.stamina().orElseThrow());
        assertEquals(NOW.plusMinutes(150), outcome.retryAt());
        assertTrue(scheduler.staminaDeferred);
        assertNull(scheduler.rescheduledAt);
        assertEquals(30, scheduler.minimumRequired);
        assertEquals(30, scheduler.regenerationTarget);
        assertEquals(outcome.retryAt(), scheduler.staminaRetryAt);
        assertEquals(NOW, scheduler.earliestRunnableAt);
    }

    @Test
    void knownSufficientStaminaAllowsIntelWithoutScheduling() {
        StaminaService service = StaminaService.getServices();
        service.setStamina(9203L, 200);
        RecordingScheduler scheduler = new RecordingScheduler();

        IntelStaminaGate.Outcome outcome = IntelStaminaGate.evaluate(service, 9203L, 30, NOW, scheduler);

        assertEquals(IntelStaminaGate.Status.READY, outcome.status());
        assertTrue(outcome.allowed());
        assertEquals(200, outcome.stamina().orElseThrow());
        assertNull(outcome.retryAt());
        assertNull(scheduler.rescheduledAt);
        assertFalse(scheduler.staminaDeferred);
    }

    @Test
    void intelligenceRoutineAllowsOnlyReadyStaminaOutcomes() {
        assertFalse(IntelligenceRoutine.mayProcessForStamina(outcome(IntelStaminaGate.Status.UNKNOWN)));
        assertFalse(IntelligenceRoutine.mayProcessForStamina(outcome(IntelStaminaGate.Status.INSUFFICIENT)));
        assertTrue(IntelligenceRoutine.mayProcessForStamina(outcome(IntelStaminaGate.Status.READY)));
    }

    @Test
    void intelligenceRoutineCallerRetriesUnknownWithoutAStaminaDeferral() {
        IntelligenceRoutine routine = routine(9210L);
        LocalDateTime before = LocalDateTime.now();

        assertFalse(routine.hasEnoughStaminaFlow());

        assertNull(routine.getStaminaDeferral());
        assertTrue(routine.getScheduled().isAfter(before.plusSeconds(50)));
        assertTrue(routine.getScheduled().isBefore(LocalDateTime.now().plusMinutes(2)));
    }

    @Test
    void intelligenceRoutineCallerDefersAMeasuredZero() {
        StaminaService.getServices().setStamina(9211L, 0);
        IntelligenceRoutine routine = routine(9211L);
        LocalDateTime before = LocalDateTime.now();

        assertFalse(routine.hasEnoughStaminaFlow());

        assertEquals(30, routine.getStaminaDeferral().minimumRequired());
        assertTrue(routine.getScheduled().isAfter(before.plusMinutes(149)));
    }

    @Test
    void intelligenceRoutineCallerAllowsMeasuredSufficientStaminaWithoutRescheduling() {
        StaminaService.getServices().setStamina(9212L, 200);
        IntelligenceRoutine routine = routine(9212L);
        LocalDateTime originalSchedule = routine.getScheduled();

        assertTrue(routine.hasEnoughStaminaFlow());

        assertNull(routine.getStaminaDeferral());
        assertEquals(originalSchedule, routine.getScheduled());
    }

    private IntelligenceRoutine routine(long profileId) {
        AccountDescriptor profile = new AccountDescriptor(profileId, "Test", "0", true, 1L, 30L);
        return new IntelligenceRoutine(profile, TpDailyTaskEnum.INTEL);
    }

    private IntelStaminaGate.Outcome outcome(IntelStaminaGate.Status status) {
        return new IntelStaminaGate.Outcome(status, java.util.OptionalInt.empty(), null);
    }

    private static final class RecordingScheduler implements StaminaWaitScheduler {
        private LocalDateTime rescheduledAt;
        private boolean staminaDeferred;
        private int minimumRequired;
        private int regenerationTarget;
        private LocalDateTime staminaRetryAt;
        private LocalDateTime earliestRunnableAt;

        @Override
        public void reschedule(LocalDateTime retryAt) {
            rescheduledAt = retryAt;
        }

        @Override
        public void deferForStamina(int minimumRequired, int regenerationTarget,
                                    LocalDateTime retryAt, LocalDateTime earliestRunnableAt) {
            staminaDeferred = true;
            this.minimumRequired = minimumRequired;
            this.regenerationTarget = regenerationTarget;
            staminaRetryAt = retryAt;
            this.earliestRunnableAt = earliestRunnableAt;
        }
    }
}
