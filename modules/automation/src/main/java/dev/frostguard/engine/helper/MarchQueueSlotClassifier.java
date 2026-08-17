package dev.frostguard.engine.helper;

import java.time.Duration;

import dev.frostguard.api.domain.MarchActivityType;
import dev.frostguard.api.domain.MarchCountdownKind;
import dev.frostguard.api.domain.MarchMovementPhase;
import dev.frostguard.api.domain.MarchResourceType;
import dev.frostguard.api.domain.MarchSlotAvailability;
import dev.frostguard.api.domain.MarchSlotState;
import dev.frostguard.api.domain.MarchSlotStatus;

final class MarchQueueSlotClassifier {

    private static final int COLOUR_PRESENT_MIN = 60;
    private static final int IDLE_WHITE_MAX = 200;
    private static final int GATHER_ICON_GREEN_MIN = 300;

    private MarchQueueSlotClassifier() {
    }

    static MarchSlotState classify(Signals signals) {
        if (signals.unlockText() || signals.unavailableText()
                || signals.orange() >= COLOUR_PRESENT_MIN || signals.red() >= COLOUR_PRESENT_MIN) {
            return new MarchSlotState(signals.slot(), MarchSlotStatus.LOCKED, MarchSlotAvailability.LOCKED,
                    MarchActivityType.NONE, MarchMovementPhase.NONE, null, null, MarchCountdownKind.NONE,
                    signals.evidence("locked"));
        }

        if (signals.white() < COLOUR_PRESENT_MIN) {
            if (signals.reinforcement()) {
                return new MarchSlotState(signals.slot(), MarchSlotStatus.STATIONED,
                        MarchSlotAvailability.OCCUPIED, MarchActivityType.REINFORCEMENT,
                        MarchMovementPhase.STATIONED, null, null, MarchCountdownKind.NONE,
                        signals.evidence("reinforcement"));
            }
            if (signals.encampment()) {
                return new MarchSlotState(signals.slot(), MarchSlotStatus.STATIONED,
                        MarchSlotAvailability.OCCUPIED, MarchActivityType.ENCAMPMENT,
                        MarchMovementPhase.STATIONED, null, null, MarchCountdownKind.NONE,
                        signals.evidence("encampment"));
            }
            if (signals.garrisoned()) {
                return new MarchSlotState(signals.slot(), MarchSlotStatus.STATIONED,
                        MarchSlotAvailability.OCCUPIED, MarchActivityType.GARRISONED,
                        MarchMovementPhase.STATIONED, null, null, MarchCountdownKind.NONE,
                        signals.evidence("garrisoned"));
            }
            if (signals.hasAnyIcon()) {
                return new MarchSlotState(signals.slot(), MarchSlotStatus.STATIONED,
                        MarchSlotAvailability.OCCUPIED, MarchActivityType.UNKNOWN,
                        MarchMovementPhase.STATIONED, null, null, MarchCountdownKind.NONE,
                        signals.evidence("stationed"));
            }
            return new MarchSlotState(signals.slot(), MarchSlotStatus.BUSY_UNKNOWN,
                    MarchSlotAvailability.UNKNOWN, MarchActivityType.UNKNOWN, MarchMovementPhase.UNKNOWN,
                    null, null, MarchCountdownKind.NONE, signals.evidence("no-status"));
        }

        if (signals.idleText() || (signals.white() < IDLE_WHITE_MAX && !signals.hasAnyIcon())) {
            return new MarchSlotState(signals.slot(), MarchSlotStatus.IDLE, MarchSlotAvailability.IDLE,
                    MarchActivityType.NONE, MarchMovementPhase.NONE, null, null, MarchCountdownKind.NONE,
                    signals.evidence("idle"));
        }

        if (signals.countdown() == null) {
            return new MarchSlotState(signals.slot(), MarchSlotStatus.BUSY_UNKNOWN,
                    MarchSlotAvailability.OCCUPIED, MarchActivityType.UNKNOWN, MarchMovementPhase.UNKNOWN,
                    null, null, MarchCountdownKind.NONE, signals.evidence("countdown-unreadable"));
        }

        if (signals.returning()) {
            return new MarchSlotState(signals.slot(), MarchSlotStatus.RETURNING, MarchSlotAvailability.OCCUPIED,
                    MarchActivityType.UNKNOWN, MarchMovementPhase.RETURNING, null, signals.countdown(),
                    MarchCountdownKind.RETURN, signals.evidence("returning"));
        }

        if (signals.rally()) {
            return new MarchSlotState(signals.slot(), MarchSlotStatus.BUSY_UNKNOWN, MarchSlotAvailability.OCCUPIED,
                    MarchActivityType.RALLY, MarchMovementPhase.PREPARING, null, signals.countdown(),
                    MarchCountdownKind.RALLY_START, signals.evidence("rally"));
        }

        if (signals.attackIcon() || signals.attackText()) {
            return new MarchSlotState(signals.slot(), MarchSlotStatus.BUSY_UNKNOWN, MarchSlotAvailability.OCCUPIED,
                    MarchActivityType.ATTACK, MarchMovementPhase.OUTBOUND, null, signals.countdown(),
                    MarchCountdownKind.ARRIVAL, signals.evidence("attack"));
        }

        if (signals.gatherGreen() >= GATHER_ICON_GREEN_MIN) {
            MarchMovementPhase phase = signals.goToText()
                    ? MarchMovementPhase.OUTBOUND
                    : signals.gatheringText() ? MarchMovementPhase.WORKING : MarchMovementPhase.UNKNOWN;
            MarchCountdownKind countdownKind = signals.goToText()
                    ? MarchCountdownKind.ARRIVAL
                    : signals.gatheringText() ? MarchCountdownKind.WORK_REMAINING : MarchCountdownKind.UNKNOWN;
            return new MarchSlotState(signals.slot(), MarchSlotStatus.GATHERING, MarchSlotAvailability.OCCUPIED,
                    MarchActivityType.GATHER, phase, signals.resourceType(), signals.countdown(), countdownKind,
                    signals.evidence("gather-active"));
        }

        return new MarchSlotState(signals.slot(), MarchSlotStatus.BUSY_UNKNOWN, MarchSlotAvailability.OCCUPIED,
                MarchActivityType.UNKNOWN, MarchMovementPhase.UNKNOWN, null, signals.countdown(),
                MarchCountdownKind.UNKNOWN, signals.evidence("busy"));
    }

    record Signals(int slot, int orange, int red, int white, int gatherGreen, boolean returning,
                   boolean rally, boolean attackIcon, boolean encampment, boolean reinforcement,
                   boolean garrisoned, boolean idleText, boolean unlockText, boolean unavailableText, boolean goToText,
                   boolean gatheringText, boolean attackText, boolean activityIconPresent, Duration countdown,
                   MarchResourceType resourceType) {

        boolean hasAnyIcon() {
            return activityIconPresent || gatherGreen >= GATHER_ICON_GREEN_MIN || returning || rally
                    || attackIcon || encampment || reinforcement || garrisoned;
        }

        String evidence(String reason) {
            return reason + " white=" + white + " green=" + gatherGreen + " orange=" + orange
                    + " red=" + red + " returning=" + returning + " rally=" + rally
                    + " attackIcon=" + attackIcon + " encampment=" + encampment
                    + " reinforcement=" + reinforcement + " garrisoned=" + garrisoned
                    + " idleText=" + idleText
                    + " unlockText=" + unlockText + " unavailableText=" + unavailableText
                    + " goToText=" + goToText + " gatheringText=" + gatheringText
                    + " attackText=" + attackText
                    + " countdown=" + countdown;
        }
    }
}
