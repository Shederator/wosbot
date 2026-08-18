package dev.frostguard.engine.helper;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.frostguard.api.domain.MarchActivityType;
import dev.frostguard.api.domain.MarchCountdownKind;
import dev.frostguard.api.domain.MarchMovementPhase;
import dev.frostguard.api.domain.MarchSlotAvailability;
import dev.frostguard.api.domain.MarchSlotState;
import dev.frostguard.api.domain.MarchSlotStatus;

class MarchHelperQueueEvidenceTest {

    @Test
    void acceptsAVisibleQueueWithoutRequestingAReset() {
        assertTrue(MarchHelper.hasReliableQueueEvidence(List.of(
                MarchSlotState.of(1, MarchSlotStatus.IDLE),
                MarchSlotState.of(2, MarchSlotStatus.GATHERING))));
    }

    @Test
    void rejectsRowsThatContainNoQueueEvidence() {
        MarchSlotState unknown = new MarchSlotState(
                1,
                MarchSlotStatus.BUSY_UNKNOWN,
                MarchSlotAvailability.UNKNOWN,
                MarchActivityType.UNKNOWN,
                MarchMovementPhase.UNKNOWN,
                null,
                null,
                MarchCountdownKind.NONE,
                "no-status");

        assertFalse(MarchHelper.hasReliableQueueEvidence(List.of(unknown)));
        assertFalse(MarchHelper.hasReliableQueueEvidence(List.of()));
    }
}
