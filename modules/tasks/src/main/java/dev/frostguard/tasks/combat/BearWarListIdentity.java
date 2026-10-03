package dev.frostguard.tasks.combat;

/** Separates War-page identity from the optional presence of joinable rally rows. */
final class BearWarListIdentity {

    private BearWarListIdentity() {
    }

    static boolean isVisible(
            boolean warTitleVisible,
            boolean selectedRallyTabVisible) {
        // Neither a generic rally indicator, a green plus nor remembered entry identifies a page.
        return warTitleVisible && selectedRallyTabVisible;
    }
}
