package dev.frostguard.engine.helper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import dev.frostguard.api.domain.MarchActivityType;
import dev.frostguard.api.domain.MarchCountdownKind;
import dev.frostguard.api.domain.MarchMovementPhase;
import dev.frostguard.api.domain.MarchResourceType;
import dev.frostguard.api.domain.MarchSlotAvailability;
import dev.frostguard.api.domain.MarchSlotReleaseConfidence;
import dev.frostguard.api.domain.MarchSlotState;
import dev.frostguard.api.domain.MarchSlotStatus;

class MarchSlotStateCompatibilityTest {

    @Test
    void legacyGatheringStatusMapsToResourceAwareDimensions() {
        MarchSlotState slot = new MarchSlotState(2, MarchSlotStatus.GATHERING, Duration.ofHours(3));

        assertEquals(MarchSlotAvailability.OCCUPIED, slot.availability());
        assertEquals(MarchActivityType.GATHER, slot.activityType());
        assertEquals(MarchMovementPhase.WORKING, slot.movementPhase());
        assertEquals(MarchResourceType.UNKNOWN, slot.resourceType());
        assertEquals(MarchCountdownKind.WORK_REMAINING, slot.countdownKind());
        assertEquals(MarchSlotReleaseConfidence.LOWER_BOUND, slot.releaseConfidence());
        assertTrue(slot.isGather());
        assertEquals(false, slot.hasExactReleaseCountdown());
        assertEquals(false, slot.hasReturnCountdown());
    }

    @Test
    void idleSlotHasNoActivityOrResourceType() {
        MarchSlotState slot = MarchSlotState.of(4, MarchSlotStatus.IDLE);

        assertTrue(slot.isIdle());
        assertEquals(MarchSlotAvailability.IDLE, slot.availability());
        assertEquals(MarchActivityType.NONE, slot.activityType());
        assertEquals(MarchMovementPhase.NONE, slot.movementPhase());
        assertNull(slot.resourceType());
        assertEquals(MarchCountdownKind.NONE, slot.countdownKind());
        assertEquals(MarchSlotReleaseConfidence.NOW, slot.releaseConfidence());
    }
}
