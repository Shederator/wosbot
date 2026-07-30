package dev.frostguard.tasks.city;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.EnumSet;
import org.junit.jupiter.api.Test;

class ConstructionBlockerRegistryTest {

    @Test
    void persistsQueueConsumersAndRetryAnchorAsOneValue() {
        LocalDateTime retryAt = LocalDateTime.of(2026, 7, 15, 20, 13, 25);
        var original = new ConstructionBlockerRegistry.Reservation(
                EnumSet.of(ConstructionBlockerRegistry.Consumer.INFANTRY), 1, retryAt);

        var restored = ConstructionBlockerRegistry.decode(
                ConstructionBlockerRegistry.encode(original)).orElseThrow();

        assertEquals(original, restored);
        assertTrue(restored.blocks(ConstructionBlockerRegistry.Consumer.INFANTRY));
        assertTrue(!restored.blocks(ConstructionBlockerRegistry.Consumer.MARKSMAN));
    }

    @Test
    void elapsedRetryAnchorDoesNotExpireTheReservation() {
        LocalDateTime elapsed = LocalDateTime.of(2026, 7, 15, 19, 0);
        var original = new ConstructionBlockerRegistry.Reservation(
                EnumSet.of(ConstructionBlockerRegistry.Consumer.RESEARCH), 2, elapsed);

        var restored = ConstructionBlockerRegistry.decode(
                ConstructionBlockerRegistry.encode(original)).orElseThrow();

        assertEquals(elapsed, restored.retryAt());
        assertTrue(restored.blocks(ConstructionBlockerRegistry.Consumer.RESEARCH));
    }

    @Test
    void rejectsMalformedPersistedReservationsConservatively() {
        assertTrue(ConstructionBlockerRegistry.decode("").isEmpty());
        assertTrue(ConstructionBlockerRegistry.decode("v1;0;123;INFANTRY").isEmpty());
        assertTrue(ConstructionBlockerRegistry.decode("v1;1;123;NOT_A_CONSUMER").isEmpty());
    }

    @Test
    void releasesOnlyWhenConstructionIsBusyAndNoQueueIsFree() {
        assertTrue(UpgradeBuildingsRoutine.shouldReleaseReservation(false, true));
        assertTrue(!UpgradeBuildingsRoutine.shouldReleaseReservation(true, true));
        assertTrue(!UpgradeBuildingsRoutine.shouldReleaseReservation(true, false));
        assertTrue(!UpgradeBuildingsRoutine.shouldReleaseReservation(false, false));
    }

    @Test
    void identifiesTrainingCampFromVisibleBuildingTitle() {
        assertEquals(ConstructionBlockerRegistry.Consumer.INFANTRY,
                UpgradeBuildingsRoutine.identifyTrainingConsumer("Infantry Camp"));
        assertEquals(ConstructionBlockerRegistry.Consumer.LANCER,
                UpgradeBuildingsRoutine.identifyTrainingConsumer("Lancer Camp"));
        assertEquals(ConstructionBlockerRegistry.Consumer.MARKSMAN,
                UpgradeBuildingsRoutine.identifyTrainingConsumer("Marksman-Camp"));
        assertTrue(UpgradeBuildingsRoutine.identifyTrainingConsumer("Camp") == null);
    }
}
