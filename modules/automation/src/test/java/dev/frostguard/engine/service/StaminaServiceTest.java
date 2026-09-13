package dev.frostguard.engine.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import dev.frostguard.engine.listener.StaminaChangeListener;
import org.junit.jupiter.api.Test;

class StaminaServiceTest {

    @Test
    void missingStaminaRemainsUnknownInsteadOfBecomingMeasuredZero() {
        StaminaService service = StaminaService.getServices();

        OptionalInt stamina = service.findCurrentStamina(9101L);

        assertTrue(stamina.isEmpty());
    }

    @Test
    void measuredZeroRemainsDistinctFromUnknown() {
        StaminaService service = StaminaService.getServices();
        service.setStamina(9102L, 0);

        OptionalInt stamina = service.findCurrentStamina(9102L);

        assertTrue(stamina.isPresent());
        assertEquals(0, stamina.getAsInt());
    }

    @Test
    void deductionBeforeFirstObservationRemainsUnknownAndRefreshable() {
        StaminaService service = StaminaService.getServices();

        service.subtractStamina(9110L, 10);

        assertTrue(service.findCurrentStamina(9110L).isEmpty());
        assertTrue(service.requiresUpdate(9110L));
    }

    @Test
    void inferredRewardsAndSpendingRemainUnknownUntilObserved() {
        StaminaService service = StaminaService.getServices();

        service.addExternalStamina(9111L, 120);
        service.subtractStamina(9111L, 100);

        assertEquals(20, service.getCurrentStamina(9111L));
        assertTrue(service.findCurrentStamina(9111L).isEmpty());
        assertTrue(service.requiresUpdate(9111L));
    }

    @Test
    void deltasAfterAnAbsoluteReadPreserveObservedState() {
        StaminaService service = StaminaService.getServices();
        service.setStamina(9112L, 20);

        service.addExternalStamina(9112L, 120);
        service.subtractStamina(9112L, 100);

        assertEquals(40, service.findCurrentStamina(9112L).orElseThrow());
    }

    @Test
    void concurrentProfilesKeepIndependentStaminaKnowledge() {
        StaminaService service = StaminaService.getServices();

        CompletableFuture.allOf(
                CompletableFuture.runAsync(() -> service.setStamina(9103L, 20)),
                CompletableFuture.runAsync(() -> service.setStamina(9104L, 200)))
                .join();

        assertEquals(20, service.findCurrentStamina(9103L).orElseThrow());
        assertEquals(200, service.findCurrentStamina(9104L).orElseThrow());
        assertTrue(service.findCurrentStamina(9105L).isEmpty());
    }

    @Test
    void setStaminaKeepsOverfilledValues() {
        StaminaService service = StaminaService.getServices();

        service.setStamina(1001L, 250);

        assertEquals(250, service.getCurrentStamina(1001L));
    }

    @Test
    void addStaminaCanOverfillAbovePassiveRegenLimit() {
        StaminaService service = StaminaService.getServices();

        service.setStamina(1002L, 190);
        service.addStamina(1002L, 25);

        assertEquals(215, service.getCurrentStamina(1002L));
    }

    @Test
    void subtractStaminaDoesNotGoBelowZero() {
        StaminaService service = StaminaService.getServices();

        service.setStamina(1003L, 10);
        service.subtractStamina(1003L, 25);

        assertEquals(0, service.getCurrentStamina(1003L));
    }

    @Test
    void externalPositiveAdditionEmitsDedicatedEventWithoutCapping() {
        StaminaService service = StaminaService.getServices();
        AtomicInteger additions = new AtomicInteger();
        AtomicReference<String> event = new AtomicReference<>();
        StaminaChangeListener listener = new StaminaChangeListener() {
            @Override
            public void onEnergyLevelChanged(Long profileId, int currentStamina) {
            }

            @Override
            public void onStaminaAdded(Long profileId, int amount, int currentStamina) {
                additions.incrementAndGet();
                event.set(profileId + ":" + amount + ":" + currentStamina);
            }
        };

        service.addStaminaChangeListener(listener);
        try {
            service.setStamina(1004L, 190);
            service.addStamina(1004L, 25);
            service.addExternalStamina(1004L, 10);
            service.subtractStamina(1004L, 5);

            assertEquals(1, additions.get());
            assertEquals("1004:10:225", event.get());
            assertEquals(220, service.getCurrentStamina(1004L));
        } finally {
            service.removeStaminaChangeListener(listener);
        }
    }

    @Test
    void absoluteReadEmitsSynchronizationEventOnlyForSet() {
        StaminaService service = StaminaService.getServices();
        AtomicInteger synchronizations = new AtomicInteger();
        AtomicReference<String> event = new AtomicReference<>();
        StaminaChangeListener listener = new StaminaChangeListener() {
            @Override
            public void onEnergyLevelChanged(Long profileId, int currentStamina) {
            }

            @Override
            public void onStaminaSynchronized(Long profileId, int currentStamina) {
                synchronizations.incrementAndGet();
                event.set(profileId + ":" + currentStamina);
            }
        };

        service.addStaminaChangeListener(listener);
        try {
            service.setStamina(1005L, 203);
            service.addStamina(1005L, 1);
            service.addExternalStamina(1005L, 120);
            service.subtractStamina(1005L, 9);

            assertEquals(1, synchronizations.get());
            assertEquals("1005:203", event.get());
        } finally {
            service.removeStaminaChangeListener(listener);
        }
    }

    @Test
    void regenerationEstimateUsesFiveMinutesPerPoint() {
        assertEquals(0, StaminaService.minutesToRegenerate(170, 170));
        assertEquals(250, StaminaService.minutesToRegenerate(120, 170));
    }
}
