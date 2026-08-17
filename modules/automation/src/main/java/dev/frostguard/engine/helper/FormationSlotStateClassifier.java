package dev.frostguard.engine.helper;

/** Classifies one visible formation tile before Frostguard interacts with it. */
final class FormationSlotStateClassifier {

    // Real 720x1280 frames measured 595-616 white pixels for saved flags and 86 for an empty slot.
    static final int SAVED_SLOT_WHITE_PIXELS_MIN = 300;

    private FormationSlotStateClassifier() {
    }

    static State classify(boolean padlocked, int whitePixels) {
        if (padlocked) {
            return State.LOCKED;
        }
        return whitePixels >= SAVED_SLOT_WHITE_PIXELS_MIN ? State.SAVED : State.EMPTY_OR_MISSING;
    }

    enum State {
        SAVED,
        LOCKED,
        EMPTY_OR_MISSING
    }
}
