package dev.frostguard.api.configs;

/**
 * Enumerates the kinds of atomic operations available in the visual
 * automation editor. Each kind maps to a single device interaction.
 */
public enum FlowStepKind {

    TAP_POINT("Tap Point", "Tap at a specific coordinate"),
    WAIT("Wait", "Pause execution for a duration"),
    SWIPE("Swipe", "Swipe between two points"),
    BACK_BUTTON("Back Button", "Press the Android back button"),
    OCR_READ("OCR Read", "Read text from a screen region"),
    TEMPLATE_SEARCH("Template Search", "Search for an image on screen"),
    NAVIGATE("Navigate", "Ensure correct screen location (Home/World)");

    private final String label;
    private final String hint;

    FlowStepKind(String label, String hint) {
        this.label = label;
        this.hint = hint;
    }

    public String label() {
        return label;
    }

    public String hint() {
        return hint;
    }

    // Legacy compatibility
    public String getDisplayName() { return label; }
    public String getDescription() { return hint; }
}
