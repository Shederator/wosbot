package dev.frostguard.tasks.combat;

/** Separates War-page identity from the optional presence of joinable rally rows. */
final class BearWarListIdentity {

    private BearWarListIdentity() {
    }

    static boolean isVisible(
            boolean openedFromVerifiedWarIndicator,
            boolean closeControlVisible,
            int visibleJoinButtons) {
        return visibleJoinButtons > 0
                || (openedFromVerifiedWarIndicator && closeControlVisible);
    }
}
