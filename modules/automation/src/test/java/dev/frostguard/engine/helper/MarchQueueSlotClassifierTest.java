package dev.frostguard.engine.helper;

import static org.junit.jupiter.api.Assertions.assertEquals;

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

class MarchQueueSlotClassifierTest {

    @Test
    void idleRequiresWeakTextAndNoActivityIcon() {
        MarchSlotState slot = classify(signals(1, 0, 0, 145, 0, false, false, null, null));

        assertEquals(MarchSlotStatus.IDLE, slot.status());
        assertEquals(MarchSlotAvailability.IDLE, slot.availability());
        assertEquals(MarchActivityType.NONE, slot.activityType());
    }

    @Test
    void idlePatternWinsDespiteGenericSlotFlag() {
        MarchSlotState slot = classify(new MarchQueueSlotClassifier.Signals(
                4, 0, 0, 260, 0, false, false, false, false, false,
                false, true, false, false, false, false, false, false, null, null));

        assertEquals(MarchSlotStatus.IDLE, slot.status());
        assertEquals(MarchSlotAvailability.IDLE, slot.availability());
    }

    @Test
    void weakTextWithIconIsStationedNotIdle() {
        MarchSlotState slot = classify(signals(1, 0, 0, 0, 0, false, true, null, null));

        assertEquals(MarchSlotStatus.STATIONED, slot.status());
        assertEquals(MarchSlotAvailability.OCCUPIED, slot.availability());
        assertEquals(MarchActivityType.UNKNOWN, slot.activityType());
        assertEquals(MarchMovementPhase.STATIONED, slot.movementPhase());
    }

    @Test
    void encampmentIconWithoutCountdownIsStationedEncampment() {
        MarchSlotState slot = classify(new MarchQueueSlotClassifier.Signals(
                1, 0, 0, 0, 0, false, false, false, true, false,
                false, false, false, false, false, false, false, true, null, null));

        assertEquals(MarchSlotStatus.STATIONED, slot.status());
        assertEquals(MarchActivityType.ENCAMPMENT, slot.activityType());
        assertEquals(MarchMovementPhase.STATIONED, slot.movementPhase());
    }

    @Test
    void reinforcementIconWithoutCountdownIsStationedReinforcement() {
        MarchSlotState slot = classify(new MarchQueueSlotClassifier.Signals(
                1, 0, 0, 0, 0, false, false, false, false, true,
                false, false, false, false, false, false, false, true, null, null));

        assertEquals(MarchSlotStatus.STATIONED, slot.status());
        assertEquals(MarchActivityType.REINFORCEMENT, slot.activityType());
        assertEquals(MarchMovementPhase.STATIONED, slot.movementPhase());
    }

    @Test
    void garrisonedIconWithoutCountdownIsStationedGarrisoned() {
        MarchSlotState slot = classify(new MarchQueueSlotClassifier.Signals(
                1, 0, 0, 0, 0, false, false, false, false, false,
                true, false, false, false, false, false, false, true, null, null));

        assertEquals(MarchSlotStatus.STATIONED, slot.status());
        assertEquals(MarchActivityType.GARRISONED, slot.activityType());
        assertEquals(MarchMovementPhase.STATIONED, slot.movementPhase());
    }

    @Test
    void weakTextWithoutIconRemainsUnknown() {
        MarchSlotState slot = classify(signals(1, 0, 0, 0, 0, false, false, null, null));

        assertEquals(MarchSlotStatus.BUSY_UNKNOWN, slot.status());
        assertEquals(MarchSlotAvailability.UNKNOWN, slot.availability());
    }

    @Test
    void lockedWinsOverOtherEvidence() {
        MarchSlotState slot = classify(signals(6, 327, 26, 0, 0, false, true, Duration.ofMinutes(5), null));

        assertEquals(MarchSlotStatus.LOCKED, slot.status());
        assertEquals(MarchSlotAvailability.LOCKED, slot.availability());
        assertEquals(MarchActivityType.NONE, slot.activityType());
    }

    @Test
    void gatherIconWithCountdownIsActiveGatherWithUnknownPhase() {
        MarchSlotState slot = classify(signals(2, 0, 0, 453, 1108, false, true,
                Duration.ofSeconds(59), MarchResourceType.MEAT));

        assertEquals(MarchSlotStatus.GATHERING, slot.status());
        assertEquals(MarchSlotAvailability.OCCUPIED, slot.availability());
        assertEquals(MarchActivityType.GATHER, slot.activityType());
        assertEquals(MarchMovementPhase.UNKNOWN, slot.movementPhase());
        assertEquals(MarchResourceType.MEAT, slot.resourceType());
        assertEquals(MarchCountdownKind.UNKNOWN, slot.countdownKind());
        assertEquals(MarchSlotReleaseConfidence.UNKNOWN, slot.releaseConfidence());
    }

    @Test
    void gatherGoToTextMarksOutboundArrivalLowerBound() {
        MarchSlotState slot = classify(new MarchQueueSlotClassifier.Signals(
                2, 0, 0, 453, 1108, false, false, false, false, false,
                false, false, false, false, true, false, false, true,
                Duration.ofSeconds(59), MarchResourceType.MEAT));

        assertEquals(MarchSlotStatus.GATHERING, slot.status());
        assertEquals(MarchActivityType.GATHER, slot.activityType());
        assertEquals(MarchMovementPhase.OUTBOUND, slot.movementPhase());
        assertEquals(MarchCountdownKind.ARRIVAL, slot.countdownKind());
        assertEquals(MarchSlotReleaseConfidence.LOWER_BOUND, slot.releaseConfidence());
    }

    @Test
    void gatherTextMarksWorkingCountdownLowerBound() {
        MarchSlotState slot = classify(new MarchQueueSlotClassifier.Signals(
                2, 0, 0, 453, 1108, false, false, false, false, false,
                false, false, false, false, false, true, false, true,
                Duration.ofSeconds(59), MarchResourceType.MEAT));

        assertEquals(MarchSlotStatus.GATHERING, slot.status());
        assertEquals(MarchActivityType.GATHER, slot.activityType());
        assertEquals(MarchMovementPhase.WORKING, slot.movementPhase());
        assertEquals(MarchCountdownKind.WORK_REMAINING, slot.countdownKind());
        assertEquals(MarchSlotReleaseConfidence.LOWER_BOUND, slot.releaseConfidence());
    }

    @Test
    void returningIconWithCountdownMarksReturnCountdown() {
        MarchSlotState slot = classify(signals(3, 0, 0, 393, 0, true, true,
                Duration.ofMinutes(2), null));

        assertEquals(MarchSlotStatus.RETURNING, slot.status());
        assertEquals(MarchMovementPhase.RETURNING, slot.movementPhase());
        assertEquals(MarchCountdownKind.RETURN, slot.countdownKind());
        assertEquals(MarchSlotReleaseConfidence.EXACT, slot.releaseConfidence());
    }

    @Test
    void rallyIconWithCountdownMarksPreparationCountdown() {
        MarchSlotState slot = classify(new MarchQueueSlotClassifier.Signals(
                3, 0, 0, 393, 0, false, true, false, false, false,
                false, false, false, false, false, false, false, true, Duration.ofSeconds(47), null));

        assertEquals(MarchSlotStatus.BUSY_UNKNOWN, slot.status());
        assertEquals(MarchActivityType.RALLY, slot.activityType());
        assertEquals(MarchMovementPhase.PREPARING, slot.movementPhase());
        assertEquals(MarchCountdownKind.RALLY_START, slot.countdownKind());
        assertEquals(MarchSlotReleaseConfidence.LOWER_BOUND, slot.releaseConfidence());
    }

    @Test
    void attackIconWithCountdownMarksOutboundArrivalCountdown() {
        MarchSlotState slot = classify(new MarchQueueSlotClassifier.Signals(
                3, 0, 0, 393, 0, false, false, true, false, false,
                false, false, false, false, false, false, false, true, Duration.ofSeconds(47), null));

        assertEquals(MarchSlotStatus.BUSY_UNKNOWN, slot.status());
        assertEquals(MarchActivityType.ATTACK, slot.activityType());
        assertEquals(MarchMovementPhase.OUTBOUND, slot.movementPhase());
        assertEquals(MarchCountdownKind.ARRIVAL, slot.countdownKind());
        assertEquals(MarchSlotReleaseConfidence.LOWER_BOUND, slot.releaseConfidence());
    }

    @Test
    void attackTextWithGenericFlagMarksOutboundArrivalCountdown() {
        MarchSlotState slot = classify(new MarchQueueSlotClassifier.Signals(
                3, 0, 0, 393, 0, false, false, false, false, false,
                false, false, false, false, false, false, true, false, Duration.ofSeconds(47), null));

        assertEquals(MarchSlotStatus.BUSY_UNKNOWN, slot.status());
        assertEquals(MarchActivityType.ATTACK, slot.activityType());
        assertEquals(MarchMovementPhase.OUTBOUND, slot.movementPhase());
        assertEquals(MarchCountdownKind.ARRIVAL, slot.countdownKind());
        assertEquals(MarchSlotReleaseConfidence.LOWER_BOUND, slot.releaseConfidence());
    }

    @Test
    void busyEvidenceWithoutReadableCountdownDoesNotBecomeIdle() {
        MarchSlotState slot = classify(signals(3, 0, 0, 393, 0, false, true, null, null));

        assertEquals(MarchSlotStatus.BUSY_UNKNOWN, slot.status());
        assertEquals(MarchSlotAvailability.OCCUPIED, slot.availability());
    }

    private static MarchSlotState classify(MarchQueueSlotClassifier.Signals signals) {
        return MarchQueueSlotClassifier.classify(signals);
    }

    private static MarchQueueSlotClassifier.Signals signals(
            int slot, int orange, int red, int white, int gatherGreen, boolean returning,
            boolean iconPresent, Duration countdown, MarchResourceType resourceType) {
        return new MarchQueueSlotClassifier.Signals(slot, orange, red, white, gatherGreen,
                returning, false, false, false, false, false, false, false, false, false, false, false,
                iconPresent, countdown, resourceType);
    }
}
