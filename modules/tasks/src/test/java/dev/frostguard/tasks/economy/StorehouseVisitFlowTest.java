package dev.frostguard.tasks.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

class StorehouseVisitFlowTest {

    private static final Duration ONE_HOUR = Duration.ofHours(1);
    private static final Duration FIVE_MINUTES = Duration.ofMinutes(5);

    @Test
    void rescansBothPoiTypesWhenStaminaAppearsAfterChestCollection() {
        ScriptedActions actions = new ScriptedActions()
                .chests(found("chest-1"), absent(), absent())
                .stamina(absent(), found("stamina-1"), absent())
                .chestCollections(StorehouseVisitFlow.CollectionState.CONFIRMED)
                .staminaCollections(StorehouseVisitFlow.CollectionState.CONFIRMED)
                .cooldown(StorehouseVisitFlow.CooldownRead.valid(Duration.ofMinutes(24)));

        StorehouseVisitFlow.VisitDecision decision = run(actions);

        assertEquals(StorehouseVisitFlow.VisitState.WAITING_COOLDOWN, decision.state());
        assertEquals(Duration.ofMinutes(24), decision.delay());
        assertEquals(1, decision.confirmedChestCollections());
        assertEquals(1, decision.confirmedStaminaCollections());
        assertEquals(3, actions.chestSearches);
        assertEquals(3, actions.staminaSearches);
        assertEquals(1, actions.cooldownReads);
    }

    @Test
    void rescansBothPoiTypesWhenChestAppearsAfterStaminaCollection() {
        ScriptedActions actions = new ScriptedActions()
                .chests(absent(), found("chest-1"), absent())
                .stamina(found("stamina-1"), absent(), absent())
                .chestCollections(StorehouseVisitFlow.CollectionState.CONFIRMED)
                .staminaCollections(StorehouseVisitFlow.CollectionState.CONFIRMED)
                .cooldown(StorehouseVisitFlow.CooldownRead.valid(Duration.ofMinutes(19)));

        StorehouseVisitFlow.VisitDecision decision = run(actions);

        assertEquals(StorehouseVisitFlow.VisitState.WAITING_COOLDOWN, decision.state());
        assertEquals(1, decision.confirmedChestCollections());
        assertEquals(1, decision.confirmedStaminaCollections());
        assertEquals(3, actions.chestSearches);
        assertEquals(3, actions.staminaSearches);
        assertEquals(1, actions.cooldownReads);
    }

    @Test
    void collectsBothInitiallyVisiblePoisThenWaitsForACompleteEmptyRescan() {
        ScriptedActions actions = new ScriptedActions()
                .chests(found("chest-1"), absent())
                .stamina(found("stamina-1"), absent())
                .chestCollections(StorehouseVisitFlow.CollectionState.CONFIRMED)
                .staminaCollections(StorehouseVisitFlow.CollectionState.CONFIRMED)
                .cooldown(StorehouseVisitFlow.CooldownRead.valid(Duration.ofMinutes(10)));

        StorehouseVisitFlow.VisitDecision decision = run(actions);

        assertEquals(StorehouseVisitFlow.VisitState.WAITING_COOLDOWN, decision.state());
        assertEquals(2, decision.confirmedChestCollections() + decision.confirmedStaminaCollections());
        assertEquals(2, actions.chestSearches);
        assertEquals(2, actions.staminaSearches);
        assertEquals(1, actions.cooldownReads);
        assertEquals(StorehouseVisitFlow.ActivityPhase.READING_COOLDOWN,
                actions.phases.get(actions.phases.size() - 1));
    }

    @Test
    void readsCooldownWhenBothPoisAreInitiallyAbsent() {
        ScriptedActions actions = new ScriptedActions()
                .chests(absent())
                .stamina(absent())
                .cooldown(StorehouseVisitFlow.CooldownRead.valid(Duration.ofMinutes(15)));

        StorehouseVisitFlow.VisitDecision decision = run(actions);

        assertEquals(StorehouseVisitFlow.VisitState.WAITING_COOLDOWN, decision.state());
        assertEquals(1, actions.cooldownReads);
        assertEquals(List.of(StorehouseVisitFlow.ActivityPhase.SEARCHING_CHEST,
                StorehouseVisitFlow.ActivityPhase.SEARCHING_STAMINA,
                StorehouseVisitFlow.ActivityPhase.READING_COOLDOWN), actions.phases);
    }

    @Test
    void doesNotReadCooldownWhenEitherPoiScanIsUnknown() {
        ScriptedActions chestUnknown = new ScriptedActions()
                .chests(unknown());
        ScriptedActions staminaUnknown = new ScriptedActions()
                .chests(absent())
                .stamina(unknown());

        assertRetry(run(chestUnknown));
        assertRetry(run(staminaUnknown));
        assertEquals(0, chestUnknown.cooldownReads);
        assertEquals(0, staminaUnknown.cooldownReads);
    }

    @Test
    void retriesWithoutCooldownWhenCollectionCannotBeConfirmed() {
        ScriptedActions actions = new ScriptedActions()
                .chests(found("chest-1"))
                .chestCollections(StorehouseVisitFlow.CollectionState.UNCONFIRMED);

        StorehouseVisitFlow.VisitDecision decision = run(actions);

        assertRetry(decision);
        assertEquals(0, actions.staminaSearches);
        assertEquals(0, actions.cooldownReads);
        assertEquals(0, decision.confirmedChestCollections());
    }

    @Test
    void retriesWhenCollectionResultIsUnknown() {
        ScriptedActions actions = new ScriptedActions()
                .chests(found("chest-1"))
                .chestCollections(StorehouseVisitFlow.CollectionState.UNKNOWN);

        StorehouseVisitFlow.VisitDecision decision = run(actions);

        assertRetry(decision);
        assertEquals(0, actions.cooldownReads);
    }

    @Test
    void retriesWhenCollectionActionLimitIsReached() {
        ScriptedActions actions = new ScriptedActions()
                .chests(found("chest-1"), found("chest-2"), found("chest-3"), found("chest-4"))
                .stamina(absent(), absent(), absent())
                .chestCollections(StorehouseVisitFlow.CollectionState.CONFIRMED,
                        StorehouseVisitFlow.CollectionState.CONFIRMED,
                        StorehouseVisitFlow.CollectionState.CONFIRMED,
                        StorehouseVisitFlow.CollectionState.CONFIRMED);

        StorehouseVisitFlow.VisitDecision decision = run(actions);

        assertRetry(decision);
        assertEquals(StorehouseVisitFlow.MAX_COLLECTIONS_PER_VISIT,
                decision.confirmedChestCollections());
        assertEquals(0, actions.cooldownReads);
    }

    @Test
    void retriesWhenVisitTimeLimitExpiresBeforeCooldownRead() {
        ScriptedActions actions = new ScriptedActions()
                .chests(absent())
                .stamina(absent())
                .cooldown(StorehouseVisitFlow.CooldownRead.valid(Duration.ofMinutes(8)));
        AtomicLong nanos = new AtomicLong();
        actions.onCooldownRead = () -> nanos.addAndGet(StorehouseVisitFlow.VISIT_TIME_LIMIT.toNanos());

        StorehouseVisitFlow.VisitDecision decision = StorehouseVisitFlow.execute(actions, nanos::get);

        assertRetry(decision);
        assertEquals(1, actions.cooldownReads);
    }

    @Test
    void preservesValidCooldownShorterThanOneHour() {
        assertValidCooldown(Duration.ofMinutes(37), Duration.ofMinutes(37));
    }

    @Test
    void capsValidCooldownLongerThanOneHourAtOneHour() {
        assertValidCooldown(Duration.ofHours(1).plusMinutes(2), ONE_HOUR);
    }

    @Test
    void capsValidCooldownLongerThanTwoHoursAtOneHour() {
        assertValidCooldown(Duration.ofHours(3).plusMinutes(15), ONE_HOUR);
    }

    @Test
    void usesOneHourFallbackForUnreadableOrInvalidCooldownWithoutErrorRetry() {
        for (StorehouseVisitFlow.CooldownRead cooldown : List.of(
                StorehouseVisitFlow.CooldownRead.unreadable("OCR returned no value"),
                StorehouseVisitFlow.CooldownRead.invalid("OCR value was not positive"))) {
            ScriptedActions actions = new ScriptedActions()
                    .chests(absent())
                    .stamina(absent())
                    .cooldown(cooldown);

            StorehouseVisitFlow.VisitDecision decision = run(actions);

            assertEquals(StorehouseVisitFlow.VisitState.WAITING_COOLDOWN, decision.state());
            assertEquals(ONE_HOUR, decision.delay());
            assertEquals(1, actions.cooldownReads);
        }
    }

    private static void assertValidCooldown(Duration cooldown, Duration expectedDelay) {
        ScriptedActions actions = new ScriptedActions()
                .chests(absent())
                .stamina(absent())
                .cooldown(StorehouseVisitFlow.CooldownRead.valid(cooldown));

        StorehouseVisitFlow.VisitDecision decision = run(actions);

        assertEquals(StorehouseVisitFlow.VisitState.WAITING_COOLDOWN, decision.state());
        assertEquals(expectedDelay, decision.delay());
        assertEquals(1, actions.cooldownReads);
    }

    private static StorehouseVisitFlow.VisitDecision run(ScriptedActions actions) {
        return StorehouseVisitFlow.execute(actions, () -> 0L);
    }

    private static void assertRetry(StorehouseVisitFlow.VisitDecision decision) {
        assertEquals(StorehouseVisitFlow.VisitState.RETRY_ON_ERROR, decision.state());
        assertEquals(FIVE_MINUTES, decision.delay());
    }

    private static StorehouseVisitFlow.Observation<String> found(String value) {
        return StorehouseVisitFlow.Observation.found(value);
    }

    private static StorehouseVisitFlow.Observation<String> absent() {
        return StorehouseVisitFlow.Observation.absent();
    }

    private static StorehouseVisitFlow.Observation<String> unknown() {
        return StorehouseVisitFlow.Observation.unknown();
    }

    private static final class ScriptedActions implements StorehouseVisitFlow.Actions<String> {
        private final Deque<StorehouseVisitFlow.Observation<String>> chestScans = new ArrayDeque<>();
        private final Deque<StorehouseVisitFlow.Observation<String>> staminaScans = new ArrayDeque<>();
        private final Deque<StorehouseVisitFlow.CollectionState> chestResults = new ArrayDeque<>();
        private final Deque<StorehouseVisitFlow.CollectionState> staminaResults = new ArrayDeque<>();
        private final Deque<StorehouseVisitFlow.CooldownRead> cooldownResults = new ArrayDeque<>();
        private final List<StorehouseVisitFlow.ActivityPhase> phases = new ArrayList<>();
        private int chestSearches;
        private int staminaSearches;
        private int cooldownReads;
        private Runnable onCooldownRead = () -> { };

        ScriptedActions chests(StorehouseVisitFlow.Observation<String>... scans) {
            chestScans.addAll(List.of(scans));
            return this;
        }

        ScriptedActions stamina(StorehouseVisitFlow.Observation<String>... scans) {
            staminaScans.addAll(List.of(scans));
            return this;
        }

        ScriptedActions chestCollections(StorehouseVisitFlow.CollectionState... results) {
            chestResults.addAll(List.of(results));
            return this;
        }

        ScriptedActions staminaCollections(StorehouseVisitFlow.CollectionState... results) {
            staminaResults.addAll(List.of(results));
            return this;
        }

        ScriptedActions cooldown(StorehouseVisitFlow.CooldownRead... results) {
            cooldownResults.addAll(List.of(results));
            return this;
        }

        @Override
        public StorehouseVisitFlow.Observation<String> findChest() {
            chestSearches++;
            return next(chestScans);
        }

        @Override
        public StorehouseVisitFlow.Observation<String> findStamina() {
            staminaSearches++;
            return next(staminaScans);
        }

        @Override
        public StorehouseVisitFlow.CollectionState collectChest(String candidate) {
            return next(chestResults);
        }

        @Override
        public StorehouseVisitFlow.CollectionState collectStamina(String candidate) {
            return next(staminaResults);
        }

        @Override
        public StorehouseVisitFlow.CooldownRead readCooldown() {
            cooldownReads++;
            onCooldownRead.run();
            return next(cooldownResults);
        }

        @Override
        public void onPhase(StorehouseVisitFlow.ActivityPhase phase) {
            phases.add(phase);
        }

        private static <V> V next(Deque<V> items) {
            if (items.isEmpty()) {
                throw new AssertionError("Unexpected extra Storehouse flow action.");
            }
            return items.removeFirst();
        }
    }
}
