package dev.frostguard.tasks.combat;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.frostguard.api.domain.AreaData;
import org.junit.jupiter.api.Test;

class BearJoinCheckpointSemanticsTest {

    @Test
    void armedStateIsAmbiguousAndMustNeverBlindlyRepeatPlus() {
        assertTrue(BearJoinCheckpointSemantics.plusMayHaveBeenCommitted("JOIN_ARMED"));
        assertTrue(BearJoinCheckpointSemantics.carriesSpatialFrontier("JOIN_ARMED"));
    }

    @Test
    void nonJoinTacticalStatesDoNotInventAListFrontier() {
        assertFalse(BearJoinCheckpointSemantics.plusMayHaveBeenCommitted("OWN_RALLY_SEND_ARMED"));
        assertFalse(BearJoinCheckpointSemantics.carriesSpatialFrontier("NONE"));
    }

    @Test
    void recoveredAmbiguousPlusKeepsItsFrontierAcrossAnotherRestart() {
        assertFalse(BearJoinCheckpointSemantics.plusMayHaveBeenCommitted(
                "RECOVERED_PLUS_ABORTED"));
        assertTrue(BearJoinCheckpointSemantics.carriesSpatialFrontier(
                "RECOVERED_PLUS_ABORTED"));
    }

    @Test
    void irreversiblePlusRequiresIdentityThatCheckpointReconstructionCanRestore() {
        AreaData plus = AreaData.of(10, 20, 40, 60);
        assertFalse(BearJoinCheckpointSemantics.hasReconstructableRowIdentity(
                row(plus, 42L, "")));
        assertFalse(BearJoinCheckpointSemantics.hasReconstructableRowIdentity(
                row(plus, 0L, "Leader")));
        assertFalse(BearJoinCheckpointSemantics.hasReconstructableRowIdentity(
                row(plus, 42L, "NONE")));
        assertTrue(BearJoinCheckpointSemantics.hasReconstructableRowIdentity(
                row(plus, 42L, "Leader")));
    }

    @Test
    void persistedJoinProvesListContextBeforeRestartDialogIsDismissed() {
        assertTrue(BearJoinCheckpointSemantics.provesWarListProvenance(
                "JOIN_ARMED", BearNavigationPolicy.Screen.DEPLOY_CONFIRMATION));
        assertTrue(BearJoinCheckpointSemantics.provesWarListProvenance(
                "PLUS_COMMITTED", BearNavigationPolicy.Screen.MARCH_QUEUE_FULL));
        assertFalse(BearJoinCheckpointSemantics.provesWarListProvenance(
                "NONE", BearNavigationPolicy.Screen.DEPLOY_CONFIRMATION));
    }

    private static BearRallyListTraversal.Row row(
            AreaData plus, long fingerprint, String leader) {
        return new BearRallyListTraversal.Row(
                new BearRallyScanner.RallyRow(plus, 100, fingerprint, leader));
    }
}
